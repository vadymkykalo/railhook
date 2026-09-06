package com.webhook.platform.worker.service;

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
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for CircuitBreakerService -- open/half-open/closed transitions,
 * thresholds, and fail-open behaviour when Redis is unavailable.
 *
 * <p>The Lua scripts themselves run inside Redis in production; here RScript.eval is
 * mocked to return the {failCount, callCount, shouldTrip, failureRate} / {callCount,
 * slowCount, shouldTrip} tuples the scripts are documented to produce (see
 * src/main/resources/lua/circuit_breaker_record_{success,failure}.lua), so this tests
 * CircuitBreakerService's own logic around those results, not Lua itself.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CircuitBreakerServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RScript rScript;

    private CircuitBreakerService service;
    private SimpleMeterRegistry meterRegistry;

    private static final int FAILURE_RATE_THRESHOLD = 50;
    private static final int MIN_CALLS = 5;
    private static final int WAIT_DURATION_SECONDS = 30;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Redisson 3.5x added an RedissonClient#getScript(OptionalOptions)
        // overload, so a bare any() is ambiguous at compile time - pin the
        // matcher's type to disambiguate to the Codec overload actually used
        // in production (see CircuitBreakerService).
        when(redissonClient.getScript(any(Codec.class))).thenReturn(rScript);
        service = new CircuitBreakerService(redissonClient, meterRegistry,
                FAILURE_RATE_THRESHOLD, MIN_CALLS, WAIT_DURATION_SECONDS, 120, 10000, 80);
    }

    @SuppressWarnings("unchecked")
    private RBucket<String> bucketFor(String key) {
        RBucket<String> bucket = mock(RBucket.class);
        when(redissonClient.<String>getBucket(eq(key))).thenReturn(bucket);
        return bucket;
    }

    @SuppressWarnings("unchecked")
    private void stubFailureEval(List<Object> result) {
        when(rScript.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST),
                anyList(), any(), any(), any())).thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessEval(List<Object> result) {
        when(rScript.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST),
                anyList(), any(), any(), any(), any(), any())).thenReturn(result);
    }

    // --- isCallPermitted (closed/open) ------------------------------------------------------

    @Test
    void isCallPermitted_noOpenMarker_permitsCall() {
        UUID endpointId = UUID.randomUUID();
        RBucket<String> openBucket = bucketFor("cb:" + endpointId + ":open");
        when(openBucket.isExists()).thenReturn(false);

        assertTrue(service.isCallPermitted(endpointId));
    }

    @Test
    void isCallPermitted_openMarkerPresent_rejectsCall() {
        UUID endpointId = UUID.randomUUID();
        RBucket<String> openBucket = bucketFor("cb:" + endpointId + ":open");
        when(openBucket.isExists()).thenReturn(true);

        assertFalse(service.isCallPermitted(endpointId));
        assertEqualsCounter(1.0, "circuit_breaker_rejected_total");
    }

    @Test
    void isCallPermitted_redisUnavailable_failsOpen() {
        UUID endpointId = UUID.randomUUID();
        when(redissonClient.<String>getBucket(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertTrue(service.isCallPermitted(endpointId), "fail-open: Redis unavailability must never block deliveries");
        assertEquals(1.0, meterRegistry.get("circuit_breaker_degraded_total").counter().count(),
                "failing open is right; failing open invisibly is the bug");
    }

    // --- recordFailure: trips open when threshold crossed -----------------------------------

    @Test
    void recordFailure_belowMinimumCalls_doesNotTrip() {
        UUID endpointId = UUID.randomUUID();
        // callCount(2) < minCalls(5) -- script itself would report shouldTrip=0.
        stubFailureEval(List.of(2L, 2L, 0L, 0L));

        service.recordFailure(endpointId, new RuntimeException("boom"));

        verify(redissonClient, never()).getBucket(eq("cb:" + endpointId + ":open"));
    }

    @Test
    void recordFailure_aboveThreshold_tripsCircuitOpen() {
        UUID endpointId = UUID.randomUUID();
        RBucket<String> openBucket = bucketFor("cb:" + endpointId + ":open");
        RKeys keys = mock(RKeys.class);
        when(redissonClient.getKeys()).thenReturn(keys);
        // 5 failures out of 5 calls = 100% >= 50% threshold -> shouldTrip=1
        stubFailureEval(List.of(5L, 5L, 1L, 100L));

        service.recordFailure(endpointId, new RuntimeException("boom"));

        verify(openBucket).set(eq("1"), eq(Duration.ofSeconds(WAIT_DURATION_SECONDS)));
        verify(keys).delete(
                "cb:" + endpointId + ":fails", "cb:" + endpointId + ":calls", "cb:" + endpointId + ":slow");
        assertEqualsCounter(1.0, "circuit_breaker_state_transitions_total");
    }

    @Test
    void recordFailure_belowThreshold_doesNotTrip() {
        UUID endpointId = UUID.randomUUID();
        // 2 failures out of 5 calls = 40% < 50% threshold -> shouldTrip=0
        stubFailureEval(List.of(2L, 5L, 0L, 40L));

        service.recordFailure(endpointId, new RuntimeException("boom"));

        verify(redissonClient, never()).getBucket(eq("cb:" + endpointId + ":open"));
    }

    @Test
    void recordFailure_redisUnavailable_doesNotThrow() {
        UUID endpointId = UUID.randomUUID();
        when(rScript.eval(any(), anyString(), any(), anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("Redis down"));

        // Best-effort: must swallow the exception, not propagate into the delivery path.
        service.recordFailure(endpointId, new RuntimeException("boom"));

        /* Each of these is an outcome the breaker did not see. Swallowed at DEBUG with no
           counter, a partially degraded Redis meant the breaker would never trip and nothing
           anywhere said so — the endpoint stayed "healthy" precisely because the health of it
           had stopped being measured. */
        assertEquals(1.0, meterRegistry.get("circuit_breaker_degraded_total").counter().count());
    }

    // --- recordSuccess: trips open on a high slow-call rate ----------------------------------

    @Test
    void recordSuccess_fastCalls_doesNotTrip() {
        UUID endpointId = UUID.randomUUID();
        // durationMs below threshold -> slowCount stays 0, shouldTrip=0
        stubSuccessEval(List.of(5L, 0L, 0L));

        service.recordSuccess(endpointId, 50L);

        verify(redissonClient, never()).getBucket(eq("cb:" + endpointId + ":open"));
    }

    @Test
    void recordSuccess_highSlowCallRate_tripsCircuitOpen() {
        UUID endpointId = UUID.randomUUID();
        RBucket<String> openBucket = bucketFor("cb:" + endpointId + ":open");
        RKeys keys = mock(RKeys.class);
        when(redissonClient.getKeys()).thenReturn(keys);
        // 5 slow calls out of 5 = 100% >= 80% slow-rate threshold -> shouldTrip=1
        stubSuccessEval(List.of(5L, 5L, 1L));

        service.recordSuccess(endpointId, 15000L);

        verify(openBucket).set(eq("1"), eq(Duration.ofSeconds(WAIT_DURATION_SECONDS)));
        assertEqualsCounter(1.0, "circuit_breaker_slow_trips_total");
        assertEqualsCounter(1.0, "circuit_breaker_state_transitions_total");
    }

    // --- reset ---------------------------------------------------------------------------------

    @Test
    void reset_deletesAllFourKeys() {
        UUID endpointId = UUID.randomUUID();
        RKeys keys = mock(RKeys.class);
        when(redissonClient.getKeys()).thenReturn(keys);

        service.reset(endpointId);

        verify(keys).delete(
                "cb:" + endpointId + ":open",
                "cb:" + endpointId + ":fails",
                "cb:" + endpointId + ":calls",
                "cb:" + endpointId + ":slow");
    }

    @Test
    void reset_redisUnavailable_doesNotThrow() {
        UUID endpointId = UUID.randomUUID();
        when(redissonClient.getKeys()).thenThrow(new RuntimeException("Redis down"));

        service.reset(endpointId);
    }

    private void assertEqualsCounter(double expected, String counterName) {
        var counter = meterRegistry.find(counterName).counter();
        org.junit.jupiter.api.Assertions.assertTrue(counter != null && counter.count() == expected,
                counterName + " expected " + expected + " but was "
                        + (counter == null ? "not registered" : counter.count()));
    }
}
