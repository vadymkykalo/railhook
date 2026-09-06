package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.AlertEventRepository;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The half of alerting that was missing: the thing that actually looks.
 *
 * <p>{@code AlertService} could always store a rule and fire an event, and
 * {@code AlertNotificationService} could always deliver one. Nothing ever decided that a rule's
 * condition had come true, so every rule a user created was inert — the UI accepted it, listed
 * it, and it never fired. All four {@link com.webhook.platform.api.domain.enums.AlertType}
 * values were unreferenced outside their enum.
 *
 * <p>Two properties this has to hold, both learned from how alerting fails elsewhere:
 *
 * <ul>
 *   <li><b>Fire on the crossing, not on the condition.</b> A rule whose condition holds for an
 *       hour must produce one alert, not sixty. That is what the unresolved-event check does:
 *       while an alert for the rule is open, the rule stays quiet. Resolving it re-arms the rule.
 *   <li><b>Every query runs as the rule's organization.</b> The scheduler is
 *       {@code @SystemTenant}, which switches Hibernate's tenant filter <i>off</i> — so counting
 *       deliveries here without re-entering the tenant would count every organization's
 *       deliveries against one customer's threshold, and the {@code AlertEvent} written at the
 *       end would belong to nobody. {@code runAs} per rule is what makes both correct.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluatorService {

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final AlertService alertService;

    /** What the evaluator concluded about one rule: the measurement, and how to say it. */
    private record Breach(double currentValue, String message) {}

    @SystemTenant("alert rules belong to every organization; each is evaluated inside its own")
    @Scheduled(cron = "${app.alerts.evaluation-cron:0 * * * * *}")
    @SchedulerLock(name = "alert_evaluation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void evaluate() {
        List<AlertRule> rules = ruleRepository.findByEnabledTrue();
        if (rules.isEmpty()) {
            return;
        }

        int fired = 0;
        for (AlertRule rule : rules) {
            try {
                if (evaluateOne(rule)) {
                    fired++;
                }
            } catch (Exception e) {
                // One malformed rule must not stop the other organizations' rules being
                // evaluated. Logged at WARN rather than swallowed: a rule that never evaluates
                // is indistinguishable from a rule that never fires, which is the bug this
                // whole class exists to fix.
                log.warn("Alert rule {} ('{}') could not be evaluated: {}",
                        rule.getId(), rule.getName(), e.toString());
            }
        }

        if (fired > 0) {
            log.info("Alert evaluation: {} rule(s) fired out of {} enabled", fired, rules.size());
        }
    }

    private boolean evaluateOne(AlertRule rule) {
        if (isSilenced(rule)) {
            return false;
        }
        return Boolean.TRUE.equals(TenantContext.callAs(rule.getOrganizationId(), () -> {
            // Checked inside the tenant, because AlertEvent is tenant-scoped too.
            if (eventRepository.existsByAlertRuleIdAndResolvedFalse(rule.getId())) {
                return false;
            }
            Optional<Breach> breach = assess(rule);
            breach.ifPresent(b -> alertService.fireAlert(rule, b.currentValue(), b.message()));
            return breach.isPresent();
        }));
    }

    /**
     * Muting and snoozing are the user saying "I know" — distinct from disabling, which is the
     * user saying "stop measuring". A muted rule is still evaluated by nobody here on purpose:
     * skipping it costs no queries.
     */
    private boolean isSilenced(AlertRule rule) {
        if (Boolean.TRUE.equals(rule.getMuted())) {
            return true;
        }
        return rule.getSnoozedUntil() != null && rule.getSnoozedUntil().isAfter(Instant.now());
    }

    private Optional<Breach> assess(AlertRule rule) {
        if (rule.getThresholdValue() == null) {
            return Optional.empty();
        }
        double threshold = rule.getThresholdValue();
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofMinutes(
                rule.getWindowMinutes() == null ? 5 : rule.getWindowMinutes()));

        return switch (rule.getAlertType()) {
            case FAILURE_RATE -> assessFailureRate(rule, threshold, from, now);
            case DLQ_THRESHOLD -> assessDlq(rule, threshold, from);
            case CONSECUTIVE_FAILURES -> assessConsecutiveFailures(rule, threshold);
            case LATENCY_THRESHOLD -> assessLatency(rule, threshold, from, now);
        };
    }

    private Optional<Breach> assessFailureRate(AlertRule rule, double threshold, Instant from, Instant to) {
        long total = deliveryRepository.countByProjectIdAndCreatedAtBetween(rule.getProjectId(), from, to);
        // No traffic is not a 100% failure rate. Without this an idle project pages someone
        // every night, and the alert people learn to ignore is worse than no alert.
        if (total == 0) {
            return Optional.empty();
        }
        long failed = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                rule.getProjectId(), DeliveryStatus.FAILED, from, to);
        double rate = (failed * 100.0) / total;
        if (rate < threshold) {
            return Optional.empty();
        }
        return Optional.of(new Breach(rate, String.format(
                "Failure rate %.1f%% over the last %d minutes (%d of %d deliveries failed), threshold %.1f%%",
                rate, minutes(rule), failed, total, threshold)));
    }

    private Optional<Breach> assessDlq(AlertRule rule, double threshold, Instant from) {
        long parked = deliveryRepository.countDlqByProjectIdSince(rule.getProjectId(), from);
        if (parked < threshold) {
            return Optional.empty();
        }
        return Optional.of(new Breach(parked, String.format(
                "%d deliveries reached the DLQ in the last %d minutes, threshold %.0f",
                parked, minutes(rule), threshold)));
    }

    private Optional<Breach> assessConsecutiveFailures(AlertRule rule, double threshold) {
        // Endpoint-shaped by nature: "the last N in a row failed" is a statement about one
        // receiver, so a rule without an endpoint has nothing to measure.
        if (rule.getEndpointId() == null) {
            return Optional.empty();
        }
        int needed = (int) Math.ceil(threshold);
        if (needed <= 0) {
            return Optional.empty();
        }
        List<DeliveryStatus> recent = deliveryRepository.findRecentOutcomesByEndpointId(
                rule.getEndpointId(), PageRequest.of(0, needed));
        // Fewer outcomes than the threshold cannot be a streak of that length — a new endpoint
        // whose first delivery failed is not "3 consecutive failures".
        if (recent.size() < needed || recent.stream().anyMatch(s -> s == DeliveryStatus.SUCCESS)) {
            return Optional.empty();
        }
        return Optional.of(new Breach(recent.size(), String.format(
                "The last %d deliveries to this endpoint all failed, threshold %d",
                recent.size(), needed)));
    }

    private Optional<Breach> assessLatency(AlertRule rule, double threshold, Instant from, Instant to) {
        // p95 rather than the mean: a mean is dragged under the threshold by the fast majority
        // exactly when a tail of slow receivers is the thing worth waking up for.
        Long p95 = attemptRepository.findLatencyPercentileByProjectId(
                rule.getOrganizationId(), rule.getProjectId(), from, to, 0.95);
        if (p95 == null || p95 < threshold) {
            return Optional.empty();
        }
        return Optional.of(new Breach(p95, String.format(
                "p95 latency %d ms over the last %d minutes, threshold %.0f ms",
                p95, minutes(rule), threshold)));
    }

    private int minutes(AlertRule rule) {
        return rule.getWindowMinutes() == null ? 5 : rule.getWindowMinutes();
    }
}
