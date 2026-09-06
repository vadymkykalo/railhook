package com.webhook.platform.api.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.webhook.platform.common.util.CryptoUtils;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@Slf4j
public class AuthRateLimiterService {

    private static final String LOGIN_IP_KEY_PREFIX = "rate_limiter:auth:login:ip:";
    private static final String LOGIN_EMAIL_KEY_PREFIX = "rate_limiter:auth:login:email:";
    private static final String LOGIN_TOKEN_KEY_PREFIX = "rate_limiter:auth:login:token:";
    private static final String REGISTER_KEY_PREFIX = "rate_limiter:auth:register:";
    private static final Duration KEY_TTL = Duration.ofMinutes(5);

    private final RedissonClient redissonClient;
    private final int loginRateLimit;
    private final int registerRateLimit;
    private final Counter authRateLimitFallback;

    /**
     * Local in-memory fallback rate limiters (Bucket4j) used when Redis is
     * unavailable.
     * Keyed by the same key as Redis (IP/email) to maintain isolation.
     * Bounded by Caffeine: max 10k entries, 5min expireAfterAccess to prevent memory growth.
     */
    private final Cache<String, Bucket> localFallbackBuckets;

    public AuthRateLimiterService(
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${auth.rate-limit.login-per-minute:10}") int loginRateLimit,
            @Value("${auth.rate-limit.register-per-minute:5}") int registerRateLimit) {
        this.redissonClient = redissonClient;
        this.loginRateLimit = loginRateLimit;
        this.registerRateLimit = registerRateLimit;

        this.localFallbackBuckets = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();

        this.authRateLimitFallback = Counter.builder("auth_rate_limit_fallback_total")
                .description("Number of auth requests rate-limited via local fallback (Redis unavailable)")
                .register(meterRegistry);
        Gauge.builder("auth_rate_limit_fallback_cache_size", localFallbackBuckets, Cache::estimatedSize)
                .description("Number of entries in the auth local fallback rate limiter cache")
                .register(meterRegistry);
    }

    public boolean allowLogin(String ip, String email) {
        boolean ipAllowed = tryAcquire(LOGIN_IP_KEY_PREFIX + ip, loginRateLimit);
        if (!ipAllowed) {
            return false;
        }
        if (email != null && !email.isBlank()) {
            return tryAcquire(LOGIN_EMAIL_KEY_PREFIX + email.toLowerCase().trim(), loginRateLimit);
        }
        return true;
    }

    public boolean allowRegister(String ip) {
        return tryAcquire(REGISTER_KEY_PREFIX + ip, registerRateLimit);
    }

    /**
     * Like {@link #allowLogin(String, String)}, but for endpoints that identify a
     * target by a token rather than an email (refresh, password reset). These have
     * no email to bucket on, so without a second dimension they are IP-only —
     * bucketing by the presented token as well means repeated guesses/retries
     * against one token are bounded even if spread across many source IPs.
     */
    public boolean allowTokenAction(String ip, String token) {
        boolean ipAllowed = tryAcquire(LOGIN_IP_KEY_PREFIX + ip, loginRateLimit);
        if (!ipAllowed) {
            return false;
        }
        if (token != null && !token.isBlank()) {
            return tryAcquire(LOGIN_TOKEN_KEY_PREFIX + CryptoUtils.hashApiKey(token), loginRateLimit);
        }
        return true;
    }

    private boolean tryAcquire(String key, int ratePerMinute) {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(key);
            limiter.trySetRate(RateType.OVERALL, ratePerMinute, 1, RateIntervalUnit.MINUTES);
            limiter.expire(KEY_TTL);
            boolean acquired = limiter.tryAcquire(1);
            if (!acquired) {
                log.warn("Auth rate limit exceeded for key: {}", key);
            }
            return acquired;
        } catch (Exception e) {
            log.warn("Auth rate limiter unavailable, using local fallback for key {}: {}", key, e.getMessage());
            authRateLimitFallback.increment();
            return tryLocalFallback(key, ratePerMinute);
        }
    }

    /**
     * Local in-memory rate limiter fallback using Bucket4j.
     * Provides emergency throttling when Redis is unavailable.
     */
    private boolean tryLocalFallback(String key, int ratePerMinute) {
        Bucket bucket = localFallbackBuckets.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(ratePerMinute)
                        .refillGreedy(ratePerMinute, Duration.ofMinutes(1))
                        .build())
                .build());

        boolean acquired = bucket.tryConsume(1);
        if (!acquired) {
            log.warn("Local fallback auth rate limit exceeded for key: {}", key);
        }
        return acquired;
    }
}
