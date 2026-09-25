package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A shared Redis key made every delivery attempt spend the plan's ingest budget. */
class ProjectRateLimiterServiceTest {

    private static final String API_INGEST_KEY_PREFIX = "rate_limiter:project:";

    @Test
    void deliveryCapDoesNotShareTheIngestLimitKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);
        ProjectRateLimiterService service = new ProjectRateLimiterService(redisson, new SimpleMeterRegistry(), 50);
        UUID projectId = UUID.randomUUID();

        assertTrue(service.tryAcquire(projectId));

        verify(redisson, never()).getRateLimiter(API_INGEST_KEY_PREFIX + projectId);
        verify(redisson).getRateLimiter(startsWith("rate_limiter:delivery:project:"));
    }
}
