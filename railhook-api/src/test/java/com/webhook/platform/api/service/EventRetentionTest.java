package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code events} and {@code deliveries} grew without bound in the deployment shape the project
 * recommends.
 *
 * <p>The only code that deleted from either by age was
 * {@code RetentionCleanupScheduler}, whose first line returns unless billing is enabled — and
 * {@code BILLING_ENABLED} defaults to false, which the README describes as what makes
 * self-hosting unrestricted. Self-hosted plans additionally carry
 * {@code max_retention_days = -1}, which that scheduler's SQL skips, so the default deployment
 * had two independent locks on never deleting anything.
 *
 * <p>Meanwhile {@code delivery_attempts} partitions were dropped at 90 days. The attempts
 * vanished on schedule and the parent rows carrying the payloads stayed forever, which is the
 * worst of both: the detail an operator needs for a post-mortem is gone, and the bytes are not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DataRetentionService — events and deliveries are bounded without billing")
class EventRetentionTest {

    @Mock private DeliveryAttemptRepository deliveryAttemptRepository;
    @Mock private IncomingEventRepository incomingEventRepository;
    @Mock private TunnelRequestLogRepository tunnelRequestLogRepository;
    @Mock private EventRepository eventRepository;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private DataRetentionService service(int eventsRetentionDays) {
        return new DataRetentionService(
                deliveryAttemptRepository, incomingEventRepository, tunnelRequestLogRepository,
                eventRepository, meterRegistry,
                // attempts, successfulAttempts, incoming, tunnelLog, maxPerDelivery, events, batch
                90, 14, 30, 7, 10, eventsRetentionDays, 1000);
    }

    @Test
    @DisplayName("deletes expired events in batches until a batch comes back short")
    void deletesInBatches() {
        when(eventRepository.deleteOldEvents(any(Instant.class), eq(1000)))
                .thenReturn(1000, 1000, 137);

        service(90).cleanupOldEvents();

        /* Draining in batches is what keeps this off a single long transaction holding row
           locks across a whole table — and, with the cascades in place, across every
           delivery and attempt beneath it. */
        verify(eventRepository, times(3)).deleteOldEvents(any(Instant.class), eq(1000));
    }

    @Test
    @DisplayName("a retention of -1 means keep everything, and issues no delete at all")
    void unlimitedRetentionDeletesNothing() {
        service(-1).cleanupOldEvents();

        /* -1 is the sentinel the plans table already uses for unlimited retention. An operator
           who wants the old behaviour has to be able to ask for it, and asking must cost no
           queries rather than a delete with an unreachable cutoff. */
        verify(eventRepository, never()).deleteOldEvents(any(), anyInt());
    }

    @Test
    @DisplayName("the cutoff is the configured number of days back, not something else")
    void cutoffMatchesConfiguredDays() {
        when(eventRepository.deleteOldEvents(any(Instant.class), anyInt())).thenReturn(0);
        Instant before = Instant.now().minusSeconds(30L * 86400L);

        service(30).cleanupOldEvents();

        var cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(eventRepository).deleteOldEvents(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue())
                .isBetween(before.minusSeconds(60), before.plusSeconds(60));
    }

    @Test
    @DisplayName("the two tables that grow are visible as gauges, not only as a disk alert")
    void exposesRowCountGauges() {
        when(eventRepository.estimatedRowCount()).thenReturn(4_200_000L);
        when(eventRepository.estimatedDeliveryRowCount()).thenReturn(9_100_000L);
        when(deliveryAttemptRepository.estimatedRowCount()).thenReturn(1L);
        when(incomingEventRepository.estimatedRowCount()).thenReturn(1L);

        DataRetentionService service = service(90);
        service.refreshTableSizeMetrics();

        /* delivery_attempts and incoming_events already had gauges. The two tables with no
           retention at all had none, so the growth that mattered most was the growth nobody
           could see. */
        assertThat(meterRegistry.get("events_table_rows").gauge().value()).isEqualTo(4_200_000d);
        assertThat(meterRegistry.get("deliveries_table_rows").gauge().value()).isEqualTo(9_100_000d);
    }
}
