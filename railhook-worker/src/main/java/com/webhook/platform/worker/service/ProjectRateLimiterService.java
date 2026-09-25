package com.webhook.platform.worker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caps what one project may take out of the shared worker pool. It is not the plan's rate limit,
 * which caps ingest, and must not be derived from it: a large plan would then be allowed to
 * starve every other tenant on the worker, which is what this exists to prevent.
 */
@Service
@Slf4j
public class ProjectRateLimiterService {

    // Not the API's rate_limiter:project: key. Sharing it made every attempt spend a permit of
    // the plan's ingest limit.
    private static final String KEY_PREFIX = "rate_limiter:delivery:project:";
    private static final Duration KEY_TTL = Duration.ofHours(24);

    private final ConvergingRateLimiter limiters;
    private final int defaultRatePerSecond;
    private final Counter projectRateLimitHits;
    private final Counter projectRateLimitExceeded;
    private final Counter projectRateLimitFallback;

    private final Cache<UUID, LocalWindow> localWindows = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();

    public ProjectRateLimiterService(
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${webhook.project-rate-limit-per-second:50}") int defaultRatePerSecond) {
        this.limiters = new ConvergingRateLimiter(redissonClient, KEY_TTL);
        this.defaultRatePerSecond = defaultRatePerSecond;
        this.projectRateLimitHits = Counter.builder("webhook_project_rate_limit_hits_total")
                .description("Deliveries that passed project-level rate limiting")
                .register(meterRegistry);
        this.projectRateLimitExceeded = Counter.builder("webhook_project_rate_limit_exceeded_total")
                .description("Deliveries throttled by project-level rate limiting")
                .register(meterRegistry);
        this.projectRateLimitFallback = Counter.builder("webhook_project_rate_limit_fallback_total")
                .description("Project rate limit checks via local fallback (Redis unavailable)")
                .register(meterRegistry);
    }

    public boolean tryAcquire(UUID projectId) {
        return tryAcquire(projectId, defaultRatePerSecond);
    }

    public boolean tryAcquire(UUID projectId, int ratePerSecond) {
        if (ratePerSecond <= 0) {
            projectRateLimitHits.increment();
            return true;
        }

        try {
            boolean acquired = limiters.tryAcquire(KEY_PREFIX + projectId, ratePerSecond);
            if (acquired) {
                projectRateLimitHits.increment();
            } else {
                projectRateLimitExceeded.increment();
                log.debug("Project rate limit exceeded for project: {} (limit: {}/sec)", projectId, ratePerSecond);
            }
            return acquired;
        } catch (Exception e) {
            log.warn("Redis project rate limiter unavailable for project {}, using local fallback: {}",
                    projectId, e.getMessage());
            projectRateLimitFallback.increment();
            return tryAcquireLocal(projectId, ratePerSecond);
        }
    }

    private boolean tryAcquireLocal(UUID projectId, int ratePerSecond) {
        LocalWindow window = localWindows.get(projectId, k -> new LocalWindow());
        long nowSecond = System.currentTimeMillis() / 1000;

        if (window.windowStart.get() != nowSecond) {
            window.windowStart.set(nowSecond);
            window.count.set(0);
        }

        int current = window.count.incrementAndGet();
        if (current <= ratePerSecond) {
            projectRateLimitHits.increment();
            return true;
        } else {
            projectRateLimitExceeded.increment();
            log.debug("Local project rate limit exceeded for project: {} (limit: {}/sec)", projectId, ratePerSecond);
            return false;
        }
    }

    private static class LocalWindow {
        final AtomicLong windowStart = new AtomicLong(0);
        final AtomicInteger count = new AtomicInteger(0);
    }
}
