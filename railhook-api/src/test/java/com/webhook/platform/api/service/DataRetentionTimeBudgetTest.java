package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A retention run has to end before its ShedLock does.
 *
 * <p>Each loop deleted until a batch came back short. Against a backlog, or a batch that takes a
 * minute because it scans the whole table, that outlived {@code lockAtMostFor}: the lock expired
 * under a run still deleting, and the next replica's run started on top of it.
 */
class DataRetentionTimeBudgetTest {

    private static final int BATCH = 1000;
    /** Far more batches than any budget allows, so a loop with no budget fails instead of spinning. */
    private static final int RUNAWAY = 500;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-17T02:00:00Z"));
    private final DeliveryAttemptRepository attempts = mock(DeliveryAttemptRepository.class);

    private final DataRetentionService service = new DataRetentionService(
            attempts, mock(IncomingEventRepository.class), mock(TunnelRequestLogRepository.class),
            mock(EventRepository.class), new SimpleMeterRegistry(), TransactionOperations.withoutTransaction(),
            clock, 90, 14, 30, 7, 10, 90, BATCH);

    @Test
    void perDeliveryLimitEnforcementStopsBeforeItsTwentyNineMinuteLock() {
        AtomicInteger batches = new AtomicInteger();
        when(attempts.deleteExcessAttemptsPerDelivery(anyInt(), anyInt())).thenAnswer(call -> {
            if (batches.incrementAndGet() > RUNAWAY) {
                throw new IllegalStateException("still deleting after " + RUNAWAY + " one-minute batches");
            }
            clock.advance(Duration.ofMinutes(1));
            return BATCH;
        });

        service.enforcePerDeliveryAttemptLimits();

        assertThat(batches.get()).isBetween(1, 28);
    }

    @Test
    void successfulAttemptCleanupStopsBeforeItsNineMinuteLock() {
        AtomicInteger batches = new AtomicInteger();
        when(attempts.deleteOldSuccessfulAttempts(any(), anyInt())).thenAnswer(call -> {
            if (batches.incrementAndGet() > RUNAWAY) {
                throw new IllegalStateException("still deleting after " + RUNAWAY + " one-minute batches");
            }
            clock.advance(Duration.ofMinutes(1));
            return BATCH;
        });

        service.cleanupOldSuccessfulAttempts();

        assertThat(batches.get()).isBetween(1, 8);
    }

    @Test
    void aBacklogInsideTheBudgetIsStillDrainedToTheEnd() {
        AtomicInteger batches = new AtomicInteger();
        when(attempts.deleteExcessAttemptsPerDelivery(anyInt(), anyInt()))
                .thenAnswer(call -> batches.incrementAndGet() < 4 ? BATCH : 7);

        service.enforcePerDeliveryAttemptLimits();

        assertThat(batches.get()).isEqualTo(4);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
