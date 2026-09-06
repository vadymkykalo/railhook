package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed circuit breaker with shared state across all worker pods.
 *
 * State model (3 Redis keys per endpoint):
 *   cb:{id}:open  — marker key with TTL = waitDuration. EXISTS → OPEN, absent → CLOSED.
 *                    When TTL expires the circuit auto-transitions to CLOSED and new calls
 *                    act as "probes" (equivalent to HALF_OPEN). If failures recur the
 *                    circuit re-opens immediately.
 *   cb:{id}:fails — failure counter in the current evaluation window (TTL-based expiry).
 *   cb:{id}:calls — total-call counter in the current evaluation window.
 *
 * On Redis failure the breaker is fail-open (permits all calls).
 */
@Service
@Slf4j
public class CircuitBreakerService {

    private static final String KEY_PREFIX = "cb:";

    private final RedissonClient redissonClient;
    private final Counter stateTransitionCounter;
    private final Counter rejectedCounter;
    private final int failureRateThreshold;
    private final int minimumNumberOfCalls;
    private final int waitDurationSeconds;
    private final int windowTtlSeconds;
    private final long slowCallThresholdMs;
    private final int slowCallRateThreshold;
    private final Counter slowTripCounter;
    private final Counter degradedCounter;
    private final String recordSuccessScript;
    private final String recordFailureScript;

    public CircuitBreakerService(
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${circuit-breaker.failure-rate-threshold:50}") int failureRateThreshold,
            @Value("${circuit-breaker.minimum-calls:5}") int minimumNumberOfCalls,
            @Value("${circuit-breaker.wait-duration-seconds:30}") int waitDurationSeconds,
            @Value("${circuit-breaker.window-ttl-seconds:120}") int windowTtlSeconds,
            @Value("${circuit-breaker.slow-call-threshold-ms:10000}") long slowCallThresholdMs,
            @Value("${circuit-breaker.slow-call-rate-threshold:80}") int slowCallRateThreshold) {
        this.redissonClient = redissonClient;
        this.failureRateThreshold = failureRateThreshold;
        this.minimumNumberOfCalls = minimumNumberOfCalls;
        this.waitDurationSeconds = waitDurationSeconds;
        this.windowTtlSeconds = windowTtlSeconds;
        this.slowCallThresholdMs = slowCallThresholdMs;
        this.slowCallRateThreshold = slowCallRateThreshold;

        this.stateTransitionCounter = Counter.builder("circuit_breaker_state_transitions_total")
                .description("Circuit breaker state transitions")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("circuit_breaker_rejected_total")
                .description("Calls rejected by open circuit breaker")
                .register(meterRegistry);
        this.slowTripCounter = Counter.builder("circuit_breaker_slow_trips_total")
                .description("Circuit breaker trips due to slow call rate")
                .register(meterRegistry);
        // Failing open when Redis is unreachable is right — a blip must not stop deliveries.
        // Failing open *silently* is not: a partially degraded Redis meant the breaker never
        // tripped and never would, and there was no signal anywhere that said so. The two
        // recording paths logged at DEBUG. RedisRateLimiterService and
        // RedisConcurrencyControlService both already publish a fallback counter; this is the
        // same idea under the same naming.
        this.degradedCounter = Counter.builder("circuit_breaker_degraded_total")
                .description("Circuit breaker operations that could not reach Redis and failed open")
                .register(meterRegistry);

        this.recordSuccessScript = loadLuaScript("lua/circuit_breaker_record_success.lua");
        this.recordFailureScript = loadLuaScript("lua/circuit_breaker_record_failure.lua");

        log.info("Redis circuit breaker initialized: failureRate={}%, minCalls={}, waitDuration={}s, windowTTL={}s, slowThreshold={}ms, slowRate={}%",
                failureRateThreshold, minimumNumberOfCalls, waitDurationSeconds, windowTtlSeconds, slowCallThresholdMs, slowCallRateThreshold);
    }

    public boolean isCallPermitted(UUID endpointId) {
        try {
            RBucket<String> openBucket = redissonClient.getBucket(openKey(endpointId));
            if (openBucket.isExists()) {
                log.warn("CircuitBreaker OPEN for endpoint {}, rejecting call", endpointId);
                rejectedCounter.increment();
                return false;
            }
            return true;
        } catch (Exception e) {
            degradedCounter.increment();
            log.warn("Redis unavailable for circuit breaker check, permitting call for endpoint {}: {}",
                    endpointId, e.getMessage());
            return true;
        }
    }

    public void recordSuccess(UUID endpointId, long durationMs) {
        try {
            RScript script = redissonClient.getScript(LongCodec.INSTANCE);
            java.util.List<Object> result = script.eval(
                    RScript.Mode.READ_WRITE,
                    recordSuccessScript,
                    RScript.ReturnType.LIST,
                    java.util.Arrays.asList(callsKey(endpointId), slowKey(endpointId)),
                    windowTtlSeconds, durationMs, slowCallThresholdMs, minimumNumberOfCalls, slowCallRateThreshold
            );

            long callCount = ((Number) result.get(0)).longValue();
            long slowCount = ((Number) result.get(1)).longValue();
            long shouldTrip = ((Number) result.get(2)).longValue();

            if (shouldTrip == 1) {
                long slowRate = (slowCount * 100) / callCount;
                log.warn("Endpoint {} slow call rate {}% >= {}% ({}ms threshold), tripping circuit",
                        endpointId, slowRate, slowCallRateThreshold, slowCallThresholdMs);
                slowTripCounter.increment();
                tripCircuit(endpointId, slowRate);
            }
        } catch (Exception e) {
            // WARN, not DEBUG: every one of these is an outcome the breaker did not see, so a
            // run of them means the breaker is not measuring anything and cannot trip.
            degradedCounter.increment();
            log.warn("Redis unavailable for circuit breaker success recording, endpoint {}: {}",
                    endpointId, e.getMessage());
        }
    }

    public void recordFailure(UUID endpointId, Throwable throwable) {
        try {
            RScript script = redissonClient.getScript(LongCodec.INSTANCE);
            java.util.List<Object> result = script.eval(
                    RScript.Mode.READ_WRITE,
                    recordFailureScript,
                    RScript.ReturnType.LIST,
                    java.util.Arrays.asList(failsKey(endpointId), callsKey(endpointId)),
                    windowTtlSeconds, minimumNumberOfCalls, failureRateThreshold
            );

            long failCount = ((Number) result.get(0)).longValue();
            long callCount = ((Number) result.get(1)).longValue();
            long shouldTrip = ((Number) result.get(2)).longValue();
            long failureRate = ((Number) result.get(3)).longValue();

            if (shouldTrip == 1) {
                tripCircuit(endpointId, failureRate);
            }
        } catch (Exception e) {
            degradedCounter.increment();
            log.warn("Redis unavailable for circuit breaker failure recording, endpoint {}: {}",
                    endpointId, e.getMessage());
        }
    }

    public void reset(UUID endpointId) {
        try {
            redissonClient.getKeys().delete(
                    openKey(endpointId),
                    failsKey(endpointId),
                    callsKey(endpointId),
                    slowKey(endpointId));
            log.info("Reset circuit breaker for endpoint: {}", endpointId);
        } catch (Exception e) {
            log.warn("Failed to reset circuit breaker for endpoint {}: {}", endpointId, e.getMessage());
        }
    }

    private void tripCircuit(UUID endpointId, long failureRate) {
        RBucket<String> openBucket = redissonClient.getBucket(openKey(endpointId));
        openBucket.set("1", Duration.ofSeconds(waitDurationSeconds));
        // Reset counters so the next evaluation window starts fresh after circuit reopens
        redissonClient.getKeys().delete(failsKey(endpointId), callsKey(endpointId), slowKey(endpointId));
        log.warn("CircuitBreaker OPENED for endpoint {} (failure rate: {}%, wait: {}s)",
                endpointId, failureRate, waitDurationSeconds);
        stateTransitionCounter.increment();
    }

    private String openKey(UUID endpointId) {
        return KEY_PREFIX + endpointId + ":open";
    }

    private String failsKey(UUID endpointId) {
        return KEY_PREFIX + endpointId + ":fails";
    }

    private String callsKey(UUID endpointId) {
        return KEY_PREFIX + endpointId + ":calls";
    }

    private String slowKey(UUID endpointId) {
        return KEY_PREFIX + endpointId + ":slow";
    }

    private String loadLuaScript(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Lua script: " + path, e);
        }
    }
}
