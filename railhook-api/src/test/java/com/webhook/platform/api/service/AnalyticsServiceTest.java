package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsServiceTest {

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryAttemptRepository attemptRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EndpointRepository endpointRepository;

    private AnalyticsService analyticsService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(orgId);
        analyticsService = new AnalyticsService(
                deliveryRepository, attemptRepository, eventRepository, endpointRepository);

        when(eventRepository.countByProjectIdAndCreatedAtBetween(eq(projectId), any(), any())).thenReturn(10L);
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(projectId), any(), any())).thenReturn(30L);
        when(deliveryRepository.findDeliveryTimeSeriesByHour(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(deliveryRepository.findDeliveryTimeSeriesByDay(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(deliveryRepository.findEndpointPerformanceByProjectId(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(eventRepository.findEventTypeBreakdownByProjectId(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(attemptRepository.findLatencyTimeSeriesByHour(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(attemptRepository.findLatencyTimeSeriesByDay(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * The delivery time series counts FAILED and DLQ together, so a chart with red
     * points next to a "FAILED 0" card is a contradiction the user has to resolve.
     * The overview has to count both terminal failure states as well.
     */
    @Test
    void overviewCountsDlqAsFailed() {
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), eq(DeliveryStatus.SUCCESS), any(), any())).thenReturn(20L);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), eq(DeliveryStatus.FAILED), any(), any())).thenReturn(3L);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), eq(DeliveryStatus.DLQ), any(), any())).thenReturn(7L);

        AnalyticsResponse response = analyticsService.getAnalytics(projectId, "24h");

        assertThat(response.getOverview().getFailedDeliveries()).isEqualTo(10L);
    }

    @Test
    void overviewReportsZeroFailuresWhenNeitherStateIsPresent() {
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), any(DeliveryStatus.class), any(), any())).thenReturn(0L);

        AnalyticsResponse response = analyticsService.getAnalytics(projectId, "7d");

        assertThat(response.getOverview().getFailedDeliveries()).isZero();
    }
}
