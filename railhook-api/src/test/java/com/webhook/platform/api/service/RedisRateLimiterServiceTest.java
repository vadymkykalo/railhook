package com.webhook.platform.api.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterServiceTest {

    @Mock
    private RedissonClient redissonClient;

    private MeterRegistry meterRegistry;
    private RedisRateLimiterService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RedisRateLimiterService(redissonClient, meterRegistry, 10);
    }

    @Test
    void tryAcquire_redisAvailable_shouldUseRedis() {
        UUID projectId = UUID.randomUUID();
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertTrue(service.tryAcquire(projectId));
        assertEquals(0, getFallbackCount());
    }

    @Test
    void tryAcquire_redisDown_shouldUseLocalFallback() {
        UUID projectId = UUID.randomUUID();
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // First call — fallback allows it (within limit)
        boolean result = service.tryAcquire(projectId);
        assertTrue(result);
        assertEquals(1, getFallbackCount());
    }

    @Test
    void tryAcquire_redisDown_localFallbackShouldEnforceLimit() {
        UUID projectId = UUID.randomUUID();
        int rateLimit = 3;
        service = new RedisRateLimiterService(redissonClient, meterRegistry, rateLimit);

        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // Consume all available tokens
        for (int i = 0; i < rateLimit; i++) {
            assertTrue(service.tryAcquire(projectId),
                    "Request " + (i + 1) + " should be allowed within limit");
        }

        // Next request should be rejected by local fallback
        assertFalse(service.tryAcquire(projectId),
                "Request exceeding limit should be rejected by local fallback");
    }

    @Test
    void getRateLimitInfo_redisDown_shouldReturnConservativeEstimate() {
        UUID projectId = UUID.randomUUID();
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        var info = service.getRateLimitInfo(projectId);

        // Should return 0 remaining (conservative/worst-case)
        assertEquals(0, info.getRemaining());
        assertEquals(10, info.getLimit());
    }

    private double getFallbackCount() {
        Counter counter = meterRegistry.find("api_rate_limit_fallback_total").counter();
        return counter != null ? counter.count() : 0;
    }
}
