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

/**
 * The delivery cap and the plan's ingest limit are two budgets, and they must be two Redis keys.
 *
 * <p>Both used {@code rate_limiter:project:<id>}. Every delivery attempt then spent a permit from
 * the bucket the API checks at ingest, so a Free project sending 8 events a second against a limit
 * of 10 had 37% of them refused with 429 — found by the first load test on production. And because
 * a Redisson rate is only set when the key is new, whichever side wrote first fixed the rate for
 * both: the 50/s delivery cap silently became the plan's 10/s.
 */
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
