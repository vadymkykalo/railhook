package com.webhook.platform.api.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Component
@Order(2)
@Slf4j
public class GlobalRateLimitFilter implements Filter {

    private static final String REDIS_KEY = "rate_limiter:global";
    private static final Duration KEY_TTL = Duration.ofHours(24);

    private final RedissonClient redissonClient;
    private final Bucket localFallbackBucket;
    private final int requestsPerSecond;
    private final boolean enabled;
    private final Counter globalRateLimitExceeded;
    private final Counter globalRateLimitFallback;
    private volatile boolean redisRateLimiterInitialized = false;

    public GlobalRateLimitFilter(
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${rate-limit.global.requests-per-second:5000}") int requestsPerSecond,
            @Value("${rate-limit.global.enabled:true}") boolean enabled) {
        this.redissonClient = redissonClient;
        this.requestsPerSecond = requestsPerSecond;
        this.enabled = enabled;
        this.localFallbackBucket = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerSecond)
                        .refillGreedy(requestsPerSecond, Duration.ofSeconds(1))
                        .build())
                .build();
        this.globalRateLimitExceeded = Counter.builder("global_rate_limit_exceeded_total")
                .description("Number of requests rejected by global rate limit")
                .register(meterRegistry);
        this.globalRateLimitFallback = Counter.builder("global_rate_limit_fallback_total")
                .description("Number of global rate limit checks using local fallback (Redis unavailable)")
                .register(meterRegistry);
        initRedisRateLimiter();
        log.info("Global rate limit filter initialized: {}/sec, enabled={}, backend=redis+local-fallback",
                requestsPerSecond, enabled);
    }

    private void initRedisRateLimiter() {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(REDIS_KEY);
            limiter.trySetRate(RateType.OVERALL, requestsPerSecond, 1, RateIntervalUnit.SECONDS);
            limiter.expire(KEY_TTL);
            redisRateLimiterInitialized = true;
        } catch (Exception e) {
            log.warn("Failed to initialize Redis rate limiter at startup, will use local fallback: {}", e.getMessage());
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // Skip health/metrics endpoints
        if (path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        if (tryAcquire()) {
            chain.doFilter(request, response);
        } else {
            globalRateLimitExceeded.increment();
            log.warn("Global platform rate limit exceeded for {} {}", httpRequest.getMethod(), path);
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(
                    "{\"error\":\"platform_rate_limit\",\"message\":\"Platform rate limit exceeded. Please retry later.\",\"status\":429}");
        }
    }

    private boolean tryAcquire() {
        try {
            if (!redisRateLimiterInitialized) {
                initRedisRateLimiter();
            }
            RRateLimiter limiter = redissonClient.getRateLimiter(REDIS_KEY);
            return limiter.tryAcquire(1);
        } catch (Exception e) {
            log.warn("Redis unavailable for global rate limit, using local fallback: {}", e.getMessage());
            globalRateLimitFallback.increment();
            return localFallbackBucket.tryConsume(1);
        }
    }
}
