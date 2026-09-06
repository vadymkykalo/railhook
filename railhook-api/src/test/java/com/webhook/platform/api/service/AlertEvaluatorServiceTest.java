package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.enums.AlertType;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.AlertEventRepository;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alerting had both ends and no middle.
 *
 * <p>{@code AlertService.fireAlert} — which writes the event, dispatches the notification and
 * opens a CRITICAL incident — had zero callers, and all four {@code AlertType} values were
 * unreferenced outside their own enum. A user could create a rule, see it listed, and it would
 * never fire. These tests are the middle, and they pin the two properties that decide whether
 * the middle is worth having: that it fires on a crossing rather than on every tick, and that
 * it counts one organization's deliveries against that organization's threshold.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AlertEvaluatorService — the half of alerting that looks")
class AlertEvaluatorServiceTest {

    @Mock private AlertRuleRepository ruleRepository;
    @Mock private AlertEventRepository eventRepository;
    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryAttemptRepository attemptRepository;
    @Mock private AlertService alertService;

    @InjectMocks private AlertEvaluatorService evaluator;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a breached failure-rate rule fires exactly one alert")
    void failureRateFires() {
        AlertRule rule = rule(AlertType.FAILURE_RATE, 50.0);
        given(rule);
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(projectId), any(), any()))
                .thenReturn(10L);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), eq(DeliveryStatus.FAILED), any(), any())).thenReturn(8L);

        evaluator.evaluate();

        verify(alertService).fireAlert(eq(rule), eq(80.0), anyString());
    }

    @Test
    @DisplayName("an idle project is not a 100% failure rate")
    void noTrafficDoesNotFire() {
        AlertRule rule = rule(AlertType.FAILURE_RATE, 50.0);
        given(rule);
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(projectId), any(), any()))
                .thenReturn(0L);

        evaluator.evaluate();

        /* Zero of zero deliveries failed. Firing here would page someone every night about a
           project nobody is using, and an alert people learn to ignore is worse than none. */
        verify(alertService, never()).fireAlert(any(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("while an alert for the rule is unresolved, the rule stays quiet")
    void firesOnTheCrossingNotEveryTick() {
        AlertRule rule = rule(AlertType.FAILURE_RATE, 50.0);
        given(rule);
        when(eventRepository.existsByAlertRuleIdAndResolvedFalse(rule.getId())).thenReturn(true);

        evaluator.evaluate();

        /* The condition is still true — that is the point. A rule whose condition holds for an
           hour must produce one alert, not sixty; resolving the event is what re-arms it. */
        verify(alertService, never()).fireAlert(any(), anyDouble(), anyString());
        verify(deliveryRepository, never()).countByProjectIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    @DisplayName("a muted rule is not evaluated, and a snoozed one is quiet until its time")
    void mutedAndSnoozedStayQuiet() {
        AlertRule muted = rule(AlertType.FAILURE_RATE, 50.0);
        muted.setMuted(true);
        AlertRule snoozed = rule(AlertType.FAILURE_RATE, 50.0);
        snoozed.setSnoozedUntil(Instant.now().plusSeconds(3600));
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(muted, snoozed));

        evaluator.evaluate();

        verify(alertService, never()).fireAlert(any(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("the alert is raised inside the rule's organization, not the scheduler's absence of one")
    void firesInsideTheRulesTenant() {
        AlertRule rule = rule(AlertType.DLQ_THRESHOLD, 5.0);
        given(rule);
        when(deliveryRepository.countDlqByProjectIdSince(eq(projectId), any())).thenReturn(9L);

        AtomicReference<UUID> tenantAtFire = new AtomicReference<>();
        when(alertService.fireAlert(any(), anyDouble(), anyString()))
                .thenAnswer(inv -> {
                    tenantAtFire.set(TenantContext.current());
                    return null;
                });

        evaluator.evaluate();

        /* The scheduler runs @SystemTenant, which switches Hibernate's tenant filter off. Were
           the rule not re-entered, the counting queries would sum every organization's
           deliveries against one customer's threshold, and the AlertEvent — itself @TenantId —
           would be written owned by nobody. */
        assertThat(tenantAtFire.get())
                .as("fireAlert must see the rule's organization as the current tenant")
                .isEqualTo(organizationId);
    }

    @Test
    @DisplayName("consecutive failures need a streak, not just a bad ratio")
    void consecutiveFailuresNeedsAnUnbrokenRun() {
        AlertRule rule = rule(AlertType.CONSECUTIVE_FAILURES, 3.0);
        rule.setEndpointId(endpointId);
        given(rule);
        when(deliveryRepository.findRecentOutcomesByEndpointId(eq(endpointId), any(Pageable.class)))
                .thenReturn(List.of(DeliveryStatus.FAILED, DeliveryStatus.SUCCESS, DeliveryStatus.FAILED));

        evaluator.evaluate();

        /* Two of the last three failed, which is a 67% failure rate and a different alert.
           A success anywhere in the window means the receiver is answering. */
        verify(alertService, never()).fireAlert(any(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("an unbroken run of failures fires")
    void consecutiveFailuresFiresOnAnUnbrokenRun() {
        AlertRule rule = rule(AlertType.CONSECUTIVE_FAILURES, 3.0);
        rule.setEndpointId(endpointId);
        given(rule);
        when(deliveryRepository.findRecentOutcomesByEndpointId(eq(endpointId), any(Pageable.class)))
                .thenReturn(List.of(DeliveryStatus.FAILED, DeliveryStatus.DLQ, DeliveryStatus.FAILED));

        evaluator.evaluate();

        verify(alertService).fireAlert(eq(rule), eq(3.0), anyString());
    }

    @Test
    @DisplayName("a new endpoint's first failure is not a streak")
    void tooFewOutcomesIsNotAStreak() {
        AlertRule rule = rule(AlertType.CONSECUTIVE_FAILURES, 3.0);
        rule.setEndpointId(endpointId);
        given(rule);
        when(deliveryRepository.findRecentOutcomesByEndpointId(eq(endpointId), any(Pageable.class)))
                .thenReturn(List.of(DeliveryStatus.FAILED));

        evaluator.evaluate();

        verify(alertService, never()).fireAlert(any(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("one unevaluatable rule does not stop the rest")
    void oneBadRuleDoesNotStopTheOthers() {
        AlertRule broken = rule(AlertType.FAILURE_RATE, 50.0);
        AlertRule healthy = rule(AlertType.DLQ_THRESHOLD, 1.0);
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(broken, healthy));
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(projectId), any(), any()))
                .thenThrow(new IllegalStateException("the query blew up"));
        when(deliveryRepository.countDlqByProjectIdSince(eq(projectId), any())).thenReturn(4L);

        evaluator.evaluate();

        /* Rules belong to different organizations. One customer's malformed rule must not
           silence every other customer's alerting for that tick. */
        verify(alertService).fireAlert(eq(healthy), eq(4.0), anyString());
    }

    @Test
    @DisplayName("a rule with no threshold measures nothing")
    void nullThresholdDoesNotFire() {
        AlertRule rule = rule(AlertType.FAILURE_RATE, null);
        given(rule);

        evaluator.evaluate();

        verify(alertService, never()).fireAlert(any(), anyDouble(), anyString());
    }

    private void given(AlertRule rule) {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
    }

    private AlertRule rule(AlertType type, Double threshold) {
        return AlertRule.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .projectId(projectId)
                .name(type + " rule")
                .alertType(type)
                .thresholdValue(threshold)
                .windowMinutes(5)
                .enabled(true)
                .muted(false)
                .build();
    }
}
