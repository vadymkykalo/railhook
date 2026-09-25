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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Fires on the crossing, not the condition: an open alert keeps its rule quiet until it clears.
 * Each rule runs in its own organization, or counts would include everyone's deliveries.
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

    private static final Duration RESOLVED_ALERT_RETENTION = Duration.ofDays(90);

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
                // WARN, not swallowed: a rule that never evaluates looks exactly like one that never fires.
                log.warn("Alert rule {} ('{}') could not be evaluated: {}",
                        rule.getId(), rule.getName(), e.toString());
            }
        }

        if (fired > 0) {
            log.info("Alert evaluation: {} rule(s) fired out of {} enabled", fired, rules.size());
        }
    }

    // Only resolved events: an open one keeps its rule quiet, and deleting it would re-fire the rule.
    @SystemTenant("alert history of every organization past its retention window")
    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "alert_event_purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purgeResolvedAlertEvents() {
        int deleted = eventRepository.deleteResolvedBefore(Instant.now().minus(RESOLVED_ALERT_RETENTION));
        if (deleted > 0) {
            log.info("Alert history: deleted {} resolved alert event(s) older than {} days",
                    deleted, RESOLVED_ALERT_RETENTION.toDays());
        }
    }

    private boolean evaluateOne(AlertRule rule) {
        if (isSilenced(rule)) {
            return false;
        }
        return Boolean.TRUE.equals(TenantContext.callAs(rule.getOrganizationId(), () -> {
            // Checked inside the tenant, because AlertEvent is tenant-scoped too.
            boolean open = eventRepository.existsByAlertRuleIdAndResolvedFalse(rule.getId());
            Optional<Breach> breach = assess(rule);
            if (open) {
                // Otherwise only a person resolves it, and the rule stays silent through later outages.
                if (breach.isEmpty()) {
                    alertService.resolveRecovered(rule);
                }
                return false;
            }
            breach.ifPresent(b -> alertService.fireAlert(rule, b.currentValue(), b.message()));
            return breach.isPresent();
        }));
    }

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
        // No traffic is not a 100% failure rate.
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
        if (rule.getEndpointId() == null) {
            return Optional.empty();
        }
        int needed = (int) Math.ceil(threshold);
        if (needed <= 0) {
            return Optional.empty();
        }
        List<DeliveryStatus> recent = deliveryRepository.findRecentOutcomesByEndpointId(
                rule.getEndpointId(), PageRequest.of(0, needed));
        // A new endpoint whose first delivery failed is not "3 consecutive failures".
        if (recent.size() < needed || recent.stream().anyMatch(s -> s == DeliveryStatus.SUCCESS)) {
            return Optional.empty();
        }
        return Optional.of(new Breach(recent.size(), String.format(
                "The last %d deliveries to this endpoint all failed, threshold %d",
                recent.size(), needed)));
    }

    private Optional<Breach> assessLatency(AlertRule rule, double threshold, Instant from, Instant to) {
        // p95, not the mean: the fast majority hides a slow tail in a mean.
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
