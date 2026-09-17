package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class RedisRateLimiterService {

    private static final String KEY_PREFIX = "rate_limiter:endpoint:";
    private static final Duration KEY_TTL = Duration.ofHours(24);

    private final ConvergingRateLimiter limiters;
    private final Counter rateLimitHits;
    private final Counter rateLimitMisses;
    private final Counter rateLimitFallback;

    private final Cache<UUID, LocalWindow> localWindows = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();

    public RedisRateLimiterService(RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.limiters = new ConvergingRateLimiter(redissonClient, KEY_TTL);
        this.rateLimitHits = Counter.builder("webhook_rate_limit_hits_total")
                .description("Number of requests that passed rate limiting")
                .register(meterRegistry);
        this.rateLimitMisses = Counter.builder("webhook_rate_limit_exceeded_total")
                .description("Number of requests rejected by rate limiting")
                .register(meterRegistry);
        this.rateLimitFallback = Counter.builder("webhook_rate_limit_fallback_total")
                .description("Number of delivery requests checked via local fallback (Redis unavailable)")
                .register(meterRegistry);
    }

    public boolean tryAcquire(UUID endpointId, int ratePerSecond) {
        if (ratePerSecond <= 0) {
            rateLimitHits.increment();
            return true;
        }

        try {
            boolean acquired = limiters.tryAcquire(KEY_PREFIX + endpointId, ratePerSecond);
            if (acquired) {
                rateLimitHits.increment();
            } else {
                rateLimitMisses.increment();
                log.debug("Rate limit exceeded for endpoint: {} (limit: {}/sec)", endpointId, ratePerSecond);
            }
            return acquired;
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable for endpoint {}, using local fallback: {}",
                    endpointId, e.getMessage());
            rateLimitFallback.increment();
            return tryAcquireLocal(endpointId, ratePerSecond);
        }
    }

    private boolean tryAcquireLocal(UUID endpointId, int ratePerSecond) {
        LocalWindow window = localWindows.get(endpointId, k -> new LocalWindow());
        long nowSecond = System.currentTimeMillis() / 1000;

        if (window.windowStart.get() != nowSecond) {
            window.windowStart.set(nowSecond);
            window.count.set(0);
        }

        int current = window.count.incrementAndGet();
        if (current <= ratePerSecond) {
            rateLimitHits.increment();
            return true;
        } else {
            rateLimitMisses.increment();
            log.debug("Local rate limit exceeded for endpoint: {} (limit: {}/sec)", endpointId, ratePerSecond);
            return false;
        }
    }

    private static class LocalWindow {
        final AtomicLong windowStart = new AtomicLong(0);
        final AtomicInteger count = new AtomicInteger(0);
    }

}
