package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.repository.DeliveryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SequenceGeneratorServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private DeliveryRepository deliveryRepository;

    private SimpleMeterRegistry meterRegistry;
    private SequenceGeneratorService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new SequenceGeneratorService(redissonClient, deliveryRepository, meterRegistry);
    }

    private UUID endpointId() {
        return UUID.randomUUID();
    }

    // Stateful fake: the reseed logic depends on real exists/compare-and-set semantics.
    private RAtomicLong wireFakeCounter(UUID endpointId) {
        RAtomicLong counter = mock(RAtomicLong.class);
        AtomicLong value = new AtomicLong(0);
        AtomicBoolean exists = new AtomicBoolean(false);

        when(counter.isExists()).thenAnswer(inv -> exists.get());
        when(counter.get()).thenAnswer(inv -> value.get());
        when(counter.incrementAndGet()).thenAnswer(inv -> {
            exists.set(true);
            return value.incrementAndGet();
        });
        when(counter.compareAndSet(anyLong(), anyLong())).thenAnswer(inv -> {
            long expect = inv.getArgument(0);
            long update = inv.getArgument(1);
            synchronized (value) {
                if (value.get() == expect) {
                    value.set(update);
                    exists.set(true);
                    return true;
                }
                return false;
            }
        });

        when(redissonClient.getAtomicLong(eq("seq:endpoint:" + endpointId))).thenReturn(counter);
        return counter;
    }

    @Test
    void nextSequence_cacheMiss_reseedsFromDurableHighWaterMark() {
        UUID endpointId = endpointId();
        wireFakeCounter(endpointId);
        when(deliveryRepository.findMaxSequenceNumber(endpointId)).thenReturn(100L);

        long next = service.nextSequence(endpointId);

        assertEquals(101L, next, "must continue from the durable high-water mark after a Redis flush");
        assertEquals(1.0, meterRegistry.counter("webhook_sequence_reseeded_total").count());
    }

    @Test
    void nextSequence_noPriorDeliveries_startsFromOne_withoutReseedMetric() {
        UUID endpointId = endpointId();
        wireFakeCounter(endpointId);
        when(deliveryRepository.findMaxSequenceNumber(endpointId)).thenReturn(null);

        long next = service.nextSequence(endpointId);

        assertEquals(1L, next);
        assertEquals(0.0, meterRegistry.counter("webhook_sequence_reseeded_total").count());
    }

    @Test
    void nextSequence_keyStillPresent_doesNotConsultDurableStore() {
        UUID endpointId = endpointId();
        RAtomicLong counter = wireFakeCounter(endpointId);
        counter.incrementAndGet();
        counter.incrementAndGet();
        counter.incrementAndGet();
        counter.incrementAndGet();
        counter.incrementAndGet();

        long next = service.nextSequence(endpointId);

        assertEquals(6L, next);
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void nextSequence_concurrentReseedRace_onlyOneWinnerNoCorruption() throws Exception {
        UUID endpointId = endpointId();
        wireFakeCounter(endpointId);
        when(deliveryRepository.findMaxSequenceNumber(endpointId)).thenReturn(50L);

        int threadCount = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> service.nextSequence(endpointId)));
            }
            Set<Long> results = new HashSet<>();
            for (Future<Long> f : futures) {
                results.add(f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(threadCount, results.size());
            assertTrue(results.stream().allMatch(v -> v > 50));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void reseedIfBehind_currentBelowMinimum_bumpsUp() {
        UUID endpointId = endpointId();
        RAtomicLong counter = wireFakeCounter(endpointId);
        counter.compareAndSet(0L, 3L);

        service.reseedIfBehind(endpointId, 10L);

        assertEquals(10L, counter.get());
    }

    @Test
    void reseedIfBehind_currentAlreadyAhead_doesNothing() {
        UUID endpointId = endpointId();
        RAtomicLong counter = wireFakeCounter(endpointId);
        counter.compareAndSet(0L, 20L);

        service.reseedIfBehind(endpointId, 10L);

        assertEquals(20L, counter.get(), "Must never move the counter backwards");
    }
}
