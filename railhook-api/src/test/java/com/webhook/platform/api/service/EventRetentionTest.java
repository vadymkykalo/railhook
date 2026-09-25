package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The only age-based cleanup ran only with billing on, so default deployments never deleted events.
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
                eventRepository, meterRegistry, TransactionOperations.withoutTransaction(), Clock.systemUTC(),
                90, 14, 30, 7, 10, eventsRetentionDays, 1000);
    }

    @Test
    @DisplayName("deletes expired events in batches until a batch comes back short")
    void deletesInBatches() {
        when(eventRepository.deleteOldEvents(any(Instant.class), eq(1000)))
                .thenReturn(1000, 1000, 137);

        service(90).cleanupOldEvents();

        verify(eventRepository, times(3)).deleteOldEvents(any(Instant.class), eq(1000));
    }

    @Test
    @DisplayName("a retention of -1 means keep everything, and issues no delete at all")
    void unlimitedRetentionDeletesNothing() {
        service(-1).cleanupOldEvents();

        verify(eventRepository, never()).deleteOldEvents(any(), anyInt());
    }

    @Test
    @DisplayName("the cutoff is the configured number of days back, not something else")
    void cutoffMatchesConfiguredDays() {
        when(eventRepository.deleteOldEvents(any(Instant.class), anyInt())).thenReturn(0);
        Instant before = Instant.now().minusSeconds(30L * 86400L);

        service(30).cleanupOldEvents();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
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

        assertThat(meterRegistry.get("events_table_rows").gauge().value()).isEqualTo(4_200_000d);
        assertThat(meterRegistry.get("deliveries_table_rows").gauge().value()).isEqualTo(9_100_000d);
    }
}
