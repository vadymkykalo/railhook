package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Counters registered on first increment left "Cleanup Deleted" reading "No data" instead of 0.
class DataRetentionMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    DataRetentionMetricsTest() {
        new DataRetentionService(mock(DeliveryAttemptRepository.class), mock(IncomingEventRepository.class),
                mock(TunnelRequestLogRepository.class), mock(EventRepository.class), registry,
                TransactionOperations.withoutTransaction(), Clock.systemUTC(),
                90, 14, 30, 7, 10, 90, 1000);
    }

    @Test
    void everyCleanupCounterExistsAtZeroBeforeTheFirstCleanup() {
        for (String type : List.of("success_age_based", "limit_based", "burst_success")) {
            assertThat(registry.get("delivery_attempts_cleanup_total").tag("type", type).counter().count())
                    .as(type).isZero();
        }
        assertThat(registry.get("incoming_events_cleanup_total").counter().count()).isZero();
        assertThat(registry.get("events_cleanup_total").counter().count()).isZero();
    }

    // Prometheus strips _total from a gauge, so the dashboard found nothing under the old name.
    @Test
    void theStoredAttemptsGaugeCarriesTheNamePrometheusExports() {
        assertThat(registry.find("delivery_attempts_total").gauge()).isNull();
        assertThat(registry.get("delivery_attempts_stored").gauge().value()).isZero();
    }
}
