package com.webhook.platform.api.filter;

import com.webhook.platform.api.service.ConvergingRateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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
    // A keep-alive, refreshed by every acquire: a TTL set once at startup lapsed a day later and
    // left each replica on its own local bucket for as long as the process lived.
    private static final Duration KEY_TTL = Duration.ofHours(24);

    private final ConvergingRateLimiter redisLimiter;
    private final Bucket localFallbackBucket;
    private final int requestsPerSecond;
    private final boolean enabled;
    private final Counter globalRateLimitExceeded;
    private final Counter globalRateLimitFallback;

    public GlobalRateLimitFilter(
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${rate-limit.global.requests-per-second:5000}") int requestsPerSecond,
            @Value("${rate-limit.global.enabled:true}") boolean enabled) {
        this.redisLimiter = new ConvergingRateLimiter(redissonClient, KEY_TTL);
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
        log.info("Global rate limit filter initialized: {}/sec, enabled={}, backend=redis+local-fallback",
                requestsPerSecond, enabled);
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
            return redisLimiter.tryAcquire(REDIS_KEY, requestsPerSecond);
        } catch (Exception e) {
            log.warn("Redis unavailable for global rate limit, using local fallback: {}", e.getMessage());
            globalRateLimitFallback.increment();
            return localFallbackBucket.tryConsume(1);
        }
    }
}
