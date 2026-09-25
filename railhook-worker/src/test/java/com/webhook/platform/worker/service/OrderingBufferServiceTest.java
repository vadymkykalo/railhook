package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.entity.OrderingCursor;
import com.webhook.platform.worker.domain.repository.OrderingCursorRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RScript.eval is an in-memory CAS mirroring the Lua script, so the cursor logic runs against a correct CAS. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderingBufferServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private OrderingCursorRepository cursorRepository;
    @Mock
    private RScript rScript;

    private SimpleMeterRegistry meterRegistry;
    private OrderingBufferService service;

    private final ConcurrentHashMap<String, Long> fakeRedisState = new ConcurrentHashMap<>();

    private static final int GAP_TIMEOUT_SECONDS = 60;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        fakeRedisState.clear();

        // any(Codec.class): a bare any() is ambiguous since Redisson added getScript(OptionalOptions).
        when(redissonClient.getScript(any(Codec.class))).thenReturn(rScript);
        // Mirrors lua/ordering_cursor_cas.lua: SET only if newVal > current, atomically.
        when(rScript.eval(eq(RScript.Mode.READ_WRITE), any(String.class), eq(RScript.ReturnType.LONG),
                any(List.class), any(), any())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(3);
            String key = keys.get(0);
            long newVal = ((Number) invocation.getArgument(4)).longValue();
            synchronized (fakeRedisState) {
                Long current = fakeRedisState.get(key);
                if (current == null || newVal > current) {
                    fakeRedisState.put(key, newVal);
                    return 1L;
                }
                return 0L;
            }
        });

        service = new OrderingBufferService(redissonClient, cursorRepository,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), meterRegistry,
                GAP_TIMEOUT_SECONDS, 24, 10);
    }

    private UUID endpointId() {
        return UUID.randomUUID();
    }

    @Test
    void markDelivered_advancesRedisFromAuthoritativePostgresValue_notFromRawArgument() {
        UUID endpointId = endpointId();
        when(cursorRepository.upsertCursor(endpointId, 5L)).thenReturn(100L);

        service.markDelivered(endpointId, 5L);

        assertEquals(100L, fakeRedisState.get("seq:delivered:" + endpointId),
                "Redis must converge to the authoritative Postgres value, never regress to the raw arg");
    }

    @Test
    void markDelivered_neverRegressesRedisCache_evenAfterSimulatedFlush() {
        UUID endpointId = endpointId();
        String key = "seq:delivered:" + endpointId;

        when(cursorRepository.upsertCursor(endpointId, 100L)).thenReturn(100L);
        service.markDelivered(endpointId, 100L);
        assertEquals(100L, fakeRedisState.get(key));

        // A Redis flush drops the key while Postgres still holds 100.
        fakeRedisState.remove(key);

        when(cursorRepository.upsertCursor(endpointId, 5L)).thenReturn(100L);
        service.markDelivered(endpointId, 5L);

        assertEquals(100L, fakeRedisState.get(key),
                "Cursor must not regress to 5 after the Redis flush -- this was the flush-regression bug");
    }

    @Test
    void markDelivered_concurrentCallsOutOfOrder_convergeToHighestValue() throws InterruptedException {
        UUID endpointId = endpointId();
        String key = "seq:delivered:" + endpointId;

        AtomicLong postgresCursor = new AtomicLong(0);
        when(cursorRepository.upsertCursor(eq(endpointId), anyLong())).thenAnswer(invocation -> {
            long candidate = invocation.getArgument(1);
            return postgresCursor.accumulateAndGet(candidate, Math::max);
        });

        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 1; i <= threadCount; i++) {
                long seq = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    service.markDelivered(endpointId, seq);
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(threadCount, fakeRedisState.get(key),
                "Concurrent out-of-order markDelivered calls must converge to the max sequence, never a lower one");
    }

    @Test
    void markDelivered_postgresWriteFails_stillAdvancesRedisBestEffortAndCountsFailure() {
        UUID endpointId = endpointId();
        when(cursorRepository.upsertCursor(endpointId, 7L)).thenThrow(new RuntimeException("db unavailable"));

        service.markDelivered(endpointId, 7L);

        assertEquals(7L, fakeRedisState.get("seq:delivered:" + endpointId));
        assertEquals(1.0, meterRegistry.counter("webhook_ordering_cursor_db_write_failed_total").count());
    }

    @Test
    void getLastDeliveredSequence_warmsRedisFromPostgresOnCacheMiss() {
        UUID endpointId = endpointId();
        RBucket<Long> bucket = mockBucket(endpointId);
        when(bucket.get()).thenReturn(null);
        when(cursorRepository.findById(endpointId)).thenReturn(Optional.of(
                OrderingCursor.builder().endpointId(endpointId).lastDeliveredSequence(42L).build()));

        Long result = service.getLastDeliveredSequence(endpointId);

        assertEquals(42L, result);
        verify(bucket).set(eq(42L), any(Duration.class));
        assertEquals(1.0, meterRegistry.counter("webhook_ordering_cache_miss_total").count());
    }

    @Test
    void canDeliver_firstDeliveryForEndpoint_onlyAllowsSequenceOne() {
        UUID endpointId = endpointId();
        RBucket<Long> bucket = mockBucket(endpointId);
        when(bucket.get()).thenReturn(null);
        when(cursorRepository.findById(endpointId)).thenReturn(Optional.empty());

        assertTrue(service.canDeliver(endpointId, 1L));
        assertFalse(service.canDeliver(endpointId, 2L));
    }

    @Test
    void canDeliver_allowsOnlyImmediateNextSequence() {
        UUID endpointId = endpointId();
        RBucket<Long> bucket = mockBucket(endpointId);
        when(bucket.get()).thenReturn(5L);

        assertTrue(service.canDeliver(endpointId, 6L));
        assertFalse(service.canDeliver(endpointId, 7L));
        assertFalse(service.canDeliver(endpointId, 5L));
    }

    @SuppressWarnings("unchecked")
    private RBucket<Long> mockBucket(UUID endpointId) {
        RBucket<Long> bucket = mock(RBucket.class);
        // The key must decode the same whether the CAS script or set() wrote it.
        when(redissonClient.<Long>getBucket(eq("seq:delivered:" + endpointId), eq(LongCodec.INSTANCE)))
                .thenReturn(bucket);
        return bucket;
    }

    @Test
    void isGapTimedOut_measuresFromWhenTheDeliveryWasFirstBuffered() {
        assertFalse(service.isGapTimedOut(null), "never buffered has not started waiting");
        assertFalse(service.isGapTimedOut(Instant.now().minusSeconds(5)));
        assertTrue(service.isGapTimedOut(Instant.now().minusSeconds(GAP_TIMEOUT_SECONDS + 5)));
    }

    @Test
    void isGapTimedOut_doesNotIncrementMetric_countingMovedToCaller() {
        // Counted here and by the caller, the gap timeout used to be double-counted.
        Instant longAgo = Instant.now().minusSeconds(GAP_TIMEOUT_SECONDS + 5);
        assertTrue(service.isGapTimedOut(longAgo));
        assertEquals(0.0, meterRegistry.counter("webhook_ordering_gap_timeout_total").count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resyncBufferSizeGauge_sumsAcrossAllEndpoints_notJustTheFirstRegistered() {
        UUID endpointA = endpointId();
        UUID endpointB = endpointId();
        String keyA = "seq:buffer:" + endpointA;
        String keyB = "seq:buffer:" + endpointB;

        RKeys keys = mock(RKeys.class);
        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysByPattern("seq:buffer:*")).thenReturn(List.of(keyA, keyB));

        RScoredSortedSet<String> bufferA = mock(RScoredSortedSet.class);
        RScoredSortedSet<String> bufferB = mock(RScoredSortedSet.class);
        when(bufferA.size()).thenReturn(3);
        when(bufferB.size()).thenReturn(5);
        when(redissonClient.<String>getScoredSortedSet(keyA)).thenReturn(bufferA);
        when(redissonClient.<String>getScoredSortedSet(keyB)).thenReturn(bufferB);

        service.resyncBufferSizeGauge();

        assertEquals(8.0, meterRegistry.find("webhook_ordering_buffer_size").gauge().value(),
                "gauge must sum every endpoint's buffer, not just the first one registered");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resyncBufferSizeGauge_returnsToZero_afterBacklogCleared() {
        UUID endpointId = endpointId();
        String key = "seq:buffer:" + endpointId;

        RKeys keys = mock(RKeys.class);
        when(redissonClient.getKeys()).thenReturn(keys);

        RScoredSortedSet<String> buffer = mock(RScoredSortedSet.class);
        when(redissonClient.<String>getScoredSortedSet(key)).thenReturn(buffer);

        when(keys.getKeysByPattern("seq:buffer:*")).thenReturn(List.of(key));
        when(buffer.size()).thenReturn(4);
        service.resyncBufferSizeGauge();
        assertEquals(4.0, meterRegistry.find("webhook_ordering_buffer_size").gauge().value());

        when(keys.getKeysByPattern("seq:buffer:*")).thenReturn(List.of());
        service.resyncBufferSizeGauge();

        assertEquals(0.0, meterRegistry.find("webhook_ordering_buffer_size").gauge().value(),
                "gauge must return to 0 once no buffer keys remain");
    }
}
