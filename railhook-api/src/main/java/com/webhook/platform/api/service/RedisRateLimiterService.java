package com.webhook.platform.api.service;

import com.webhook.platform.api.dto.RateLimitInfo;
import com.webhook.platform.api.dto.RateLimitResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class RedisRateLimiterService {

    private static final String KEY_PREFIX = "rate_limiter:project:";
    private static final String SOURCE_KEY_PREFIX = "rate_limiter:source:";
    private static final String ORGANIZATION_KEY_PREFIX = "rate_limiter:org:";
    private static final String PORTAL_SESSION_KEY_PREFIX = "rate_limiter:portal_session:";
    private static final Duration KEY_TTL = Duration.ofHours(24);

    private static final Duration SLUG_KEY_TTL = Duration.ofMinutes(10);

    private final RedissonClient redissonClient;
    private final ConvergingRateLimiter limiters;
    private final ConvergingRateLimiter slugLimiters;
    private final int defaultRateLimit;
    private final Counter rateLimitHits;
    private final Counter rateLimitExceeded;
    private final Counter rateLimitFallback;
    private final Counter rateLimitFailClosed;

    private final Cache<UUID, Bucket> localFallbackBuckets;

    public RedisRateLimiterService(
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${event.ingestion.rate-limit-per-second:100}") int defaultRateLimit) {
        this.redissonClient = redissonClient;
        this.limiters = new ConvergingRateLimiter(redissonClient, KEY_TTL);
        this.slugLimiters = new ConvergingRateLimiter(redissonClient, SLUG_KEY_TTL);
        this.defaultRateLimit = defaultRateLimit;

        this.localFallbackBuckets = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();

        this.rateLimitHits = Counter.builder("api_rate_limit_hits_total")
                .description("Number of requests that passed rate limiting")
                .register(meterRegistry);
        this.rateLimitExceeded = Counter.builder("api_rate_limit_exceeded_total")
                .description("Number of requests rejected by rate limiting")
                .register(meterRegistry);
        this.rateLimitFallback = Counter.builder("api_rate_limit_fallback_total")
                .description("Number of requests rate-limited via local fallback (Redis unavailable)")
                .register(meterRegistry);
        this.rateLimitFailClosed = Counter.builder("api_rate_limit_fail_closed_total")
                .description("Number of requests rejected due to Redis outage in fail-closed mode")
                .register(meterRegistry);
        Gauge.builder("api_rate_limit_fallback_cache_size", localFallbackBuckets, Cache::estimatedSize)
                .description("Number of entries in the local fallback rate limiter cache")
                .register(meterRegistry);
    }

    public boolean tryAcquire(UUID projectId) {
        return tryAcquire(projectId, defaultRateLimit);
    }

    public boolean tryAcquire(UUID projectId, int ratePerSecond) {
        return doTryAcquire(KEY_PREFIX + projectId, projectId, ratePerSecond);
    }

    public RateLimitResult tryAcquireWithInfo(UUID projectId) {
        return tryAcquireWithInfo(projectId, defaultRateLimit);
    }

    public RateLimitResult tryAcquireWithInfo(UUID projectId, int ratePerSecond) {
        try {
            String key = KEY_PREFIX + projectId;
            boolean acquired = limiters.tryAcquire(key, ratePerSecond);
            long available = redissonClient.getRateLimiter(key).availablePermits();
            int remaining = (int) Math.max(0, Math.min(available, ratePerSecond));
            long resetTimestamp = Instant.now().plusSeconds(1).getEpochSecond();

            if (acquired) {
                rateLimitHits.increment();
            } else {
                rateLimitExceeded.increment();
                log.warn("Rate limit exceeded for project: {} (limit: {}/sec)", projectId, ratePerSecond);
            }

            RateLimitInfo info = RateLimitInfo.builder()
                    .limit(ratePerSecond)
                    .remaining(acquired ? remaining : 0)
                    .resetTimestamp(resetTimestamp)
                    .build();

            return RateLimitResult.builder()
                    .acquired(acquired)
                    .info(info)
                    .retryAfterSeconds(acquired ? 0 : 1)
                    .build();
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable, using local fallback for project {}: {}",
                    projectId, e.getMessage());
            rateLimitFallback.increment();
            boolean acquired = tryLocalFallback(projectId, ratePerSecond);
            RateLimitInfo info = RateLimitInfo.builder()
                    .limit(ratePerSecond)
                    .remaining(acquired ? ratePerSecond - 1 : 0)
                    .resetTimestamp(Instant.now().plusSeconds(1).getEpochSecond())
                    .build();
            return RateLimitResult.builder()
                    .acquired(acquired)
                    .info(info)
                    .retryAfterSeconds(acquired ? 0 : 1)
                    .build();
        }
    }

    public boolean tryAcquireForSource(UUID sourceId, int ratePerSecond) {
        return doTryAcquire(SOURCE_KEY_PREFIX + sourceId, sourceId, ratePerSecond);
    }

    // Fails open. The global filter's single bucket let one tenant exhaust it for everyone.
    public boolean tryAcquireForOrganization(UUID organizationId, int ratePerSecond) {
        return doTryAcquire(ORGANIZATION_KEY_PREFIX + organizationId, organizationId, ratePerSecond);
    }

    // Fails open. A portal session is the customer's user, so it does not draw on the org budget.
    public boolean tryAcquireForPortalSession(UUID sessionId, int ratePerSecond) {
        return doTryAcquire(PORTAL_SESSION_KEY_PREFIX + sessionId, sessionId, ratePerSecond);
    }

    /**
     * Fails closed: a Redis outage must not let unbounded public ingress reach the database.
     */
    public boolean tryAcquireForSourceFailClosed(UUID sourceId, int ratePerSecond) {
        try {
            boolean acquired = limiters.tryAcquire(SOURCE_KEY_PREFIX + sourceId, ratePerSecond);
            if (acquired) {
                rateLimitHits.increment();
            } else {
                rateLimitExceeded.increment();
                log.warn("Rate limit exceeded for source: {} (limit: {}/sec)", sourceId, ratePerSecond);
            }
            return acquired;
        } catch (Exception e) {
            log.error("Redis unavailable — fail-closed rejecting ingress for source {}: {}",
                    sourceId, e.getMessage());
            rateLimitFailClosed.increment();
            return false;
        }
    }

    private static final String SLUG_KEY_PREFIX = "rate_limiter:slug:";

    public boolean tryAcquireForSlug(String slug, int ratePerSecond) {
        try {
            boolean acquired = slugLimiters.tryAcquire(SLUG_KEY_PREFIX + slug, ratePerSecond);
            if (acquired) {
                rateLimitHits.increment();
            } else {
                rateLimitExceeded.increment();
                log.warn("Rate limit exceeded for slug: {} (limit: {}/sec)", slug, ratePerSecond);
            }
            return acquired;
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable, using local fallback for slug {}: {}",
                    slug, e.getMessage());
            rateLimitFallback.increment();
            return tryLocalSlugFallback(slug, ratePerSecond);
        }
    }

    private final Cache<String, Bucket> localSlugFallbackBuckets = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();

    private boolean tryLocalSlugFallback(String slug, int ratePerSecond) {
        Bucket bucket = localSlugFallbackBuckets.get(slug, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(ratePerSecond)
                        .refillGreedy(ratePerSecond, Duration.ofSeconds(1))
                        .build())
                .build());
        return bucket.tryConsume(1);
    }

    private boolean doTryAcquire(String key, UUID id, int ratePerSecond) {
        try {
            boolean acquired = limiters.tryAcquire(key, ratePerSecond);
            if (acquired) {
                rateLimitHits.increment();
            } else {
                rateLimitExceeded.increment();
                log.warn("Rate limit exceeded for id: {} (limit: {}/sec)", id, ratePerSecond);
            }
            return acquired;
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable, using local fallback for id {}: {}",
                    id, e.getMessage());
            rateLimitFallback.increment();
            return tryLocalFallback(id, ratePerSecond);
        }
    }

    public long getSecondsToWaitForRefill(UUID projectId) {
        try {
            String key = KEY_PREFIX + projectId;
            RRateLimiter limiter = redissonClient.getRateLimiter(key);
            return Math.max(1, limiter.availablePermits() > 0 ? 0 : 1);
        } catch (Exception e) {
            return 1;
        }
    }

    public RateLimitInfo getRateLimitInfo(UUID projectId) {
        return getRateLimitInfo(projectId, defaultRateLimit);
    }

    public RateLimitInfo getRateLimitInfo(UUID projectId, int ratePerSecond) {
        try {
            String key = KEY_PREFIX + projectId;
            RRateLimiter limiter = redissonClient.getRateLimiter(key);
            long available = limiter.availablePermits();
            int remaining = (int) Math.max(0, Math.min(available, ratePerSecond));
            long resetTimestamp = Instant.now().plusSeconds(1).getEpochSecond();

            return RateLimitInfo.builder()
                    .limit(ratePerSecond)
                    .remaining(remaining)
                    .resetTimestamp(resetTimestamp)
                    .build();
        } catch (Exception e) {
            log.debug("Unable to get rate limit info, returning conservative estimate: {}", e.getMessage());
            return RateLimitInfo.builder()
                    .limit(ratePerSecond)
                    .remaining(0)
                    .resetTimestamp(Instant.now().plusSeconds(1).getEpochSecond())
                    .build();
        }
    }

    private boolean tryLocalFallback(UUID projectId, int ratePerSecond) {
        Bucket bucket = localFallbackBuckets.get(projectId, id -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(ratePerSecond)
                        .refillGreedy(ratePerSecond, Duration.ofSeconds(1))
                        .build())
                .build());

        boolean acquired = bucket.tryConsume(1);
        if (acquired) {
            rateLimitHits.increment();
        } else {
            rateLimitExceeded.increment();
            log.warn("Local fallback rate limit exceeded for project: {} (limit: {}/sec)", projectId, ratePerSecond);
        }
        return acquired;
    }
}
