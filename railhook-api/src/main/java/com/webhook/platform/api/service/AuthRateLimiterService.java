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
    private static final String PLATFORM_ADMIN_KEY_PREFIX = "rate_limiter:platform_admin:";
    static final int PLATFORM_ADMIN_PER_MINUTE = 120;
    private static final String REFRESH_IP_KEY_PREFIX = "rate_limiter:auth:refresh:ip:";
    private static final String REFRESH_TOKEN_KEY_PREFIX = "rate_limiter:auth:refresh:token:";
    private static final String DEVICE_POLL_IP_KEY_PREFIX = "rate_limiter:auth:device_poll:ip:";
    private static final String DEVICE_POLL_CODE_KEY_PREFIX = "rate_limiter:auth:device_poll:code:";
    /** The CLI polls every five seconds, twelve a minute; the rest is room for a clock that drifts. */
    static final int DEVICE_POLL_PER_CODE_PER_MINUTE = 20;
    static final int DEVICE_POLL_PER_IP_PER_MINUTE = 120;
    private static final String PUBLIC_BIN_KEY_PREFIX = "rate_limiter:public_bin:ip:";
    /** Public tester URLs a single address may make in a minute; a person needs one or two. */
    static final int PUBLIC_BIN_PER_IP_PER_MINUTE = 5;
    private static final String OAUTH_REGISTER_KEY_PREFIX = "rate_limiter:oauth:register:ip:";
    /**
     * Not the sign-up bucket's 5: a hosted app registers from its own servers, so every person who
     * adds the connector there in the same minute arrives from the same few addresses.
     */
    static final int OAUTH_REGISTER_PER_IP_PER_MINUTE = 60;
    private static final String OAUTH_TOKEN_IP_KEY_PREFIX = "rate_limiter:oauth:token:ip:";
    private static final String OAUTH_TOKEN_CLIENT_KEY_PREFIX = "rate_limiter:oauth:token:client:";
    /** High, because one hosted app (claude.ai, ChatGPT) refreshes every user's connection from few addresses. */
    static final int OAUTH_TOKEN_PER_IP_PER_MINUTE = 600;
    static final int OAUTH_TOKEN_PER_CLIENT_PER_MINUTE = 300;
    private static final Duration KEY_TTL = Duration.ofMinutes(5);

    private final RedissonClient redissonClient;
    private final int loginRateLimit;
    private final int registerRateLimit;
    private final int refreshPerTokenRateLimit;
    private final int refreshPerIpRateLimit;
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
            @Value("${auth.rate-limit.register-per-minute:5}") int registerRateLimit,
            @Value("${auth.rate-limit.refresh-per-token-per-minute:30}") int refreshPerTokenRateLimit,
            @Value("${auth.rate-limit.refresh-per-ip-per-minute:600}") int refreshPerIpRateLimit) {
        this.redissonClient = redissonClient;
        this.loginRateLimit = loginRateLimit;
        this.registerRateLimit = registerRateLimit;
        this.refreshPerTokenRateLimit = refreshPerTokenRateLimit;
        this.refreshPerIpRateLimit = refreshPerIpRateLimit;

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

    /** Making a webhook tester URL on the public site, which needs no account. */
    public boolean allowPublicBin(String ip) {
        return tryAcquire(PUBLIC_BIN_KEY_PREFIX + ip, PUBLIC_BIN_PER_IP_PER_MINUTE);
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

    /**
     * The platform admin API, per admin user (or per address for the operator token). Generous
     * for a person clicking through the panel — a screen is a handful of requests — and a hard
     * stop for a script walking every organization with a stolen session.
     */
    public boolean allowPlatformAdmin(String caller) {
        return tryAcquire(PLATFORM_ADMIN_KEY_PREFIX + caller, PLATFORM_ADMIN_PER_MINUTE);
    }

    /**
     * Session refresh, on a budget of its own.
     *
     * <p>Refresh used to go through {@link #allowTokenAction}, which spends the per-IP sign-in
     * bucket. A dashboard refreshes its session on page loads, so ten of them in a minute logged a
     * signed-in person out with a 429 — and everyone behind one office NAT shared those ten.
     *
     * <p>A refresh presents a token the server issued, so the useful bound is per token: enough
     * for a busy person with several tabs, low enough that a stolen cookie cannot be spun freely.
     * The per-IP ceiling sits far above normal browsing and only stops one peer cycling through
     * many different tokens. The sign-in bucket is not touched.
     */
    public boolean allowRefresh(String ip, String token) {
        if (!tryAcquire(REFRESH_IP_KEY_PREFIX + ip, refreshPerIpRateLimit)) {
            return false;
        }
        if (token != null && !token.isBlank()) {
            return tryAcquire(REFRESH_TOKEN_KEY_PREFIX + CryptoUtils.hashApiKey(token), refreshPerTokenRateLimit);
        }
        return true;
    }

    /**
     * A CLI waiting for its device code to be approved, on a budget of its own.
     *
     * <p>Polling used to go through {@link #allowTokenAction}, spending the per-IP sign-in bucket
     * twelve times a minute against a limit of ten. The person approving that code in a browser is
     * nearly always on the same address, so the approval itself was refused with a 429 and the CLI
     * could not be logged in at all.
     *
     * <p>A device code is long and random, so guessing one is not what this bounds. Per code it is
     * a client polling faster than the interval it was given; per IP, one peer cycling through
     * codes. The sign-in bucket is left to approve and deny, where the short user code is the
     * enumeration target.
     */
    public boolean allowDevicePoll(String ip, String deviceCode) {
        if (!tryAcquire(DEVICE_POLL_IP_KEY_PREFIX + ip, DEVICE_POLL_PER_IP_PER_MINUTE)) {
            return false;
        }
        if (deviceCode != null && !deviceCode.isBlank()) {
            return tryAcquire(DEVICE_POLL_CODE_KEY_PREFIX + CryptoUtils.hashApiKey(deviceCode),
                    DEVICE_POLL_PER_CODE_PER_MINUTE);
        }
        return true;
    }

    /**
     * An MCP app registering itself (OAuth dynamic client registration). Open by necessity — the
     * app has no account to register under — so bounded per address. A script minting clients in a
     * loop is what this stops.
     */
    public boolean allowOAuthRegister(String ip) {
        return tryAcquire(OAUTH_REGISTER_KEY_PREFIX + ip, OAUTH_REGISTER_PER_IP_PER_MINUTE);
    }

    /**
     * The OAuth token and revocation endpoints. Per address, and per client so that one app
     * guessing at codes or refresh tokens is bounded however many addresses it comes from. An app
     * in normal use calls this once an hour per connection.
     */
    public boolean allowOAuthToken(String ip, String clientId) {
        if (!tryAcquire(OAUTH_TOKEN_IP_KEY_PREFIX + ip, OAUTH_TOKEN_PER_IP_PER_MINUTE)) {
            return false;
        }
        if (clientId != null && !clientId.isBlank()) {
            return tryAcquire(OAUTH_TOKEN_CLIENT_KEY_PREFIX + CryptoUtils.hashApiKey(clientId),
                    OAUTH_TOKEN_PER_CLIENT_PER_MINUTE);
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
