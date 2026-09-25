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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// AlertService.fireAlert once had no callers: rules could be created and never fired.
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

        verify(alertService, never()).fireAlert(any(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("while an alert for the rule is unresolved and the condition still holds, the rule stays quiet")
    void firesOnTheCrossingNotEveryTick() {
        AlertRule rule = rule(AlertType.FAILURE_RATE, 50.0);
        given(rule);
        when(eventRepository.existsByAlertRuleIdAndResolvedFalse(rule.getId())).thenReturn(true);
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(projectId), any(), any()))
                .thenReturn(10L);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), eq(DeliveryStatus.FAILED), any(), any())).thenReturn(8L);

        evaluator.evaluate();

        verify(alertService, never()).fireAlert(any(), anyDouble(), anyString());
        verify(alertService, never()).resolveRecovered(any());
    }

    @Test
    @DisplayName("fire once per crossing: breach fires, recovery resolves, the next breach fires again")
    void firesAgainAfterTheConditionRecovers() {
        AlertRule rule = rule(AlertType.DLQ_THRESHOLD, 5.0);
        given(rule);
        AtomicBoolean open = new AtomicBoolean(false);
        when(eventRepository.existsByAlertRuleIdAndResolvedFalse(rule.getId())).thenAnswer(inv -> open.get());
        when(alertService.fireAlert(any(), anyDouble(), anyString())).thenAnswer(inv -> {
            open.set(true);
            return null;
        });
        when(alertService.resolveRecovered(rule)).thenAnswer(inv -> {
            open.set(false);
            return 1;
        });

        when(deliveryRepository.countDlqByProjectIdSince(eq(projectId), any())).thenReturn(9L);
        evaluator.evaluate();
        evaluator.evaluate();
        verify(alertService, times(1)).fireAlert(eq(rule), eq(9.0), anyString());

        when(deliveryRepository.countDlqByProjectIdSince(eq(projectId), any())).thenReturn(0L);
        evaluator.evaluate();
        // Before the evaluator resolved on recovery, a rule fired once and then stayed silent for good.
        verify(alertService).resolveRecovered(rule);
        assertThat(open.get()).as("the open alert is resolved once the condition stops holding").isFalse();

        when(deliveryRepository.countDlqByProjectIdSince(eq(projectId), any())).thenReturn(7L);
        evaluator.evaluate();
        verify(alertService).fireAlert(eq(rule), eq(7.0), anyString());
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

        // The scheduler runs @SystemTenant; without re-entering, counts would span every organization.
        assertThat(tenantAtFire.get()).isEqualTo(organizationId);
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

        verify(alertService).fireAlert(eq(healthy), eq(4.0), anyString());
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
