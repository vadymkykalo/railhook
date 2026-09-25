package com.webhook.platform.api.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * trySetRate never updates an existing key, so the stored rate is compared and rewritten. A lost
 * limiter ("RateLimiter is not initialized") is re-created and retried once.
 */
public final class ConvergingRateLimiter {

    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final String NOT_INITIALIZED = "RateLimiter is not initialized";

    private final RedissonClient redissonClient;
    private final Duration keepAlive;
    private final Cache<String, Long> appliedRates = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public ConvergingRateLimiter(RedissonClient redissonClient, Duration keepAlive) {
        this.redissonClient = redissonClient;
        this.keepAlive = keepAlive;
    }

    public boolean tryAcquire(String key, long ratePerSecond) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        applyRate(key, limiter, ratePerSecond);
        try {
            return limiter.tryAcquire(1);
        } catch (RuntimeException e) {
            if (!isNotInitialized(e)) {
                throw e;
            }
            appliedRates.invalidate(key);
            applyRate(key, limiter, ratePerSecond);
            return limiter.tryAcquire(1);
        }
    }

    private void applyRate(String key, RRateLimiter limiter, long ratePerSecond) {
        Long applied = appliedRates.getIfPresent(key);
        if (applied != null && applied == ratePerSecond) {
            return;
        }
        if (!limiter.trySetRate(RateType.OVERALL, ratePerSecond, INTERVAL, keepAlive)) {
            RateLimiterConfig stored = limiter.getConfig();
            if (!matches(stored, ratePerSecond)) {
                limiter.setRate(RateType.OVERALL, ratePerSecond, INTERVAL, keepAlive);
            }
        }
        appliedRates.put(key, ratePerSecond);
    }

    private static boolean matches(RateLimiterConfig stored, long ratePerSecond) {
        return stored != null
                && stored.getRateType() == RateType.OVERALL
                && stored.getRate() != null && stored.getRate() == ratePerSecond
                && stored.getRateInterval() != null && stored.getRateInterval() == INTERVAL.toMillis();
    }

    private static boolean isNotInitialized(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(NOT_INITIALIZED)) {
                return true;
            }
        }
        return false;
    }
}
