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
    /** The CLI polls every five seconds, twelve a minute. The rest is room for clock drift. */
    static final int DEVICE_POLL_PER_CODE_PER_MINUTE = 20;
    static final int DEVICE_POLL_PER_IP_PER_MINUTE = 120;
    private static final String PUBLIC_BIN_KEY_PREFIX = "rate_limiter:public_bin:ip:";
    static final int PUBLIC_BIN_PER_IP_PER_MINUTE = 5;
    private static final String CONTACT_KEY_PREFIX = "rate_limiter:contact:ip:";
    static final int CONTACT_PER_IP_PER_MINUTE = 2;
    private static final String DEMO_SESSION_KEY_PREFIX = "rate_limiter:demo_session:ip:";
    static final int DEMO_SESSION_PER_IP_PER_MINUTE = 10;
    private static final String DEMO_SCRIPT_SESSION_KEY_PREFIX = "rate_limiter:demo_script:session:";
    private static final String DEMO_SCRIPT_IP_KEY_PREFIX = "rate_limiter:demo_script:ip:";
    private static final String OAUTH_REGISTER_KEY_PREFIX = "rate_limiter:oauth:register:ip:";
    /** Hosted apps register from their own servers, so many users arrive from a few addresses. */
    static final int OAUTH_REGISTER_PER_IP_PER_MINUTE = 60;
    private static final String OAUTH_TOKEN_IP_KEY_PREFIX = "rate_limiter:oauth:token:ip:";
    private static final String OAUTH_TOKEN_CLIENT_KEY_PREFIX = "rate_limiter:oauth:token:client:";
    /** One hosted app (claude.ai, ChatGPT) refreshes every user's connection from few addresses. */
    static final int OAUTH_TOKEN_PER_IP_PER_MINUTE = 600;
    static final int OAUTH_TOKEN_PER_CLIENT_PER_MINUTE = 300;
    private static final Duration KEY_TTL = Duration.ofMinutes(5);

    private final RedissonClient redissonClient;
    private final int loginRateLimit;
    private final int registerRateLimit;
    private final int refreshPerTokenRateLimit;
    private final int refreshPerIpRateLimit;
    private final int demoScriptPerSessionRateLimit;
    private final int demoScriptPerIpRateLimit;
    private final Counter authRateLimitFallback;

    private final Cache<String, Bucket> localFallbackBuckets;

    public AuthRateLimiterService(
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${auth.rate-limit.login-per-minute:10}") int loginRateLimit,
            @Value("${auth.rate-limit.register-per-minute:5}") int registerRateLimit,
            @Value("${auth.rate-limit.refresh-per-token-per-minute:30}") int refreshPerTokenRateLimit,
            @Value("${auth.rate-limit.refresh-per-ip-per-minute:600}") int refreshPerIpRateLimit,
            @Value("${demo.script-runs.per-session-per-minute:20}") int demoScriptPerSessionRateLimit,
            @Value("${demo.script-runs.per-ip-per-minute:60}") int demoScriptPerIpRateLimit) {
        this.redissonClient = redissonClient;
        this.loginRateLimit = loginRateLimit;
        this.registerRateLimit = registerRateLimit;
        this.refreshPerTokenRateLimit = refreshPerTokenRateLimit;
        this.refreshPerIpRateLimit = refreshPerIpRateLimit;
        this.demoScriptPerSessionRateLimit = demoScriptPerSessionRateLimit;
        this.demoScriptPerIpRateLimit = demoScriptPerIpRateLimit;

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

    public boolean allowPublicBin(String ip) {
        return tryAcquire(PUBLIC_BIN_KEY_PREFIX + ip, PUBLIC_BIN_PER_IP_PER_MINUTE);
    }

    public boolean allowContactMessage(String ip) {
        return tryAcquire(CONTACT_KEY_PREFIX + ip, CONTACT_PER_IP_PER_MINUTE);
    }

    public boolean allowDemoSession(String ip) {
        return tryAcquire(DEMO_SESSION_KEY_PREFIX + ip, DEMO_SESSION_PER_IP_PER_MINUTE);
    }

    // Scripts hold a request thread: 60 two-second runs a minute is at most two threads per address.
    public boolean allowDemoScriptRun(String ip, String sessionToken) {
        if (!tryAcquire(DEMO_SCRIPT_IP_KEY_PREFIX + ip, demoScriptPerIpRateLimit)) {
            return false;
        }
        if (sessionToken != null && !sessionToken.isBlank()) {
            return tryAcquire(DEMO_SCRIPT_SESSION_KEY_PREFIX + CryptoUtils.hashApiKey(sessionToken),
                    demoScriptPerSessionRateLimit);
        }
        return true;
    }

    public boolean allowRegister(String ip) {
        return tryAcquire(REGISTER_KEY_PREFIX + ip, registerRateLimit);
    }

    /** Bucketing by the token too bounds guesses spread across many addresses. */
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

    public boolean allowPlatformAdmin(String caller) {
        return tryAcquire(PLATFORM_ADMIN_KEY_PREFIX + caller, PLATFORM_ADMIN_PER_MINUTE);
    }

    // Its own bucket: sharing the sign-in one logged out a whole office behind one NAT.
    public boolean allowRefresh(String ip, String token) {
        if (!tryAcquire(REFRESH_IP_KEY_PREFIX + ip, refreshPerIpRateLimit)) {
            return false;
        }
        if (token != null && !token.isBlank()) {
            return tryAcquire(REFRESH_TOKEN_KEY_PREFIX + CryptoUtils.hashApiKey(token), refreshPerTokenRateLimit);
        }
        return true;
    }

    // Its own bucket: polling in the sign-in one got the approval from the same address a 429.
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

    public boolean allowOAuthRegister(String ip) {
        return tryAcquire(OAUTH_REGISTER_KEY_PREFIX + ip, OAUTH_REGISTER_PER_IP_PER_MINUTE);
    }

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
