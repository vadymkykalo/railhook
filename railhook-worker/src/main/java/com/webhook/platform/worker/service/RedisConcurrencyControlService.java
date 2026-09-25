package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class RedisConcurrencyControlService {

    private static final Duration KEY_TTL = Duration.ofHours(24);

    /**
     * TARGET stops one slow receiver taking the pool. It does nothing about a tenant with twenty
     * slow endpoints, whose sum is the whole worker; TENANT caps that sum.
     */
    public enum Scope {
        TENANT("concurrency:tenant:"),
        TARGET("concurrency:endpoint:");

        private final String prefix;

        Scope(String prefix) {
            this.prefix = prefix;
        }
    }

    private final RedissonClient redissonClient;
    private final ConcurrentHashMap<String, String> acquiredPermits = new ConcurrentHashMap<>();
    private final Cache<String, AtomicInteger> localPermits = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();

    private final Cache<String, Boolean> initializedSemaphores = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(20))
            .build();
    private final int maxConcurrentPerEndpoint;
    private final int maxConcurrentPerTenant;
    private final int permitLeaseSeconds;
    private final Counter concurrencyAcquired;
    private final Map<Scope, Counter> concurrencyRejectedByScope = new EnumMap<>(Scope.class);
    private final Counter concurrencyReleased;
    private final Counter concurrencyFallback;
    private final AtomicInteger activePermits = new AtomicInteger(0);

    public RedisConcurrencyControlService(
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${webhook.max-concurrent-per-endpoint:10}") int maxConcurrentPerEndpoint,
            @Value("${webhook.max-concurrent-per-tenant:20}") int maxConcurrentPerTenant,
            @Value("${webhook.concurrency.permit-lease-seconds:90}") int permitLeaseSeconds) {
        this.redissonClient = redissonClient;
        this.maxConcurrentPerEndpoint = maxConcurrentPerEndpoint;
        this.maxConcurrentPerTenant = maxConcurrentPerTenant;
        this.permitLeaseSeconds = permitLeaseSeconds;
        
        this.concurrencyAcquired = Counter.builder("webhook_concurrency_acquired_total")
                .description("Number of concurrency permits acquired")
                .register(meterRegistry);
        for (Scope scope : Scope.values()) {
            concurrencyRejectedByScope.put(scope, Counter.builder("webhook_concurrency_rejected_total")
                    .description("Number of concurrency permits rejected")
                    .tag("scope", scope.name().toLowerCase())
                    .register(meterRegistry));
        }
        this.concurrencyReleased = Counter.builder("webhook_concurrency_released_total")
                .description("Number of concurrency permits released")
                .register(meterRegistry);
        this.concurrencyFallback = Counter.builder("webhook_concurrency_fallback_total")
                .description("Number of concurrency checks via local fallback (Redis unavailable)")
                .register(meterRegistry);
        
        Gauge.builder("webhook_concurrency_active_permits", activePermits, AtomicInteger::get)
                .description("Number of currently held permits")
                .register(meterRegistry);
    }

    public boolean tryAcquireForTenant(UUID tenantId) {
        return tryAcquire(Scope.TENANT, tenantId);
    }

    public boolean tryAcquireForTarget(UUID targetId) {
        return tryAcquire(Scope.TARGET, targetId);
    }

    public void releaseForTenant(UUID tenantId) {
        release(Scope.TENANT, tenantId);
    }

    public void releaseForTarget(UUID targetId) {
        release(Scope.TARGET, targetId);
    }

    private int limitFor(Scope scope) {
        return scope == Scope.TENANT ? maxConcurrentPerTenant : maxConcurrentPerEndpoint;
    }

    public boolean tryAcquire(Scope scope, UUID endpointId) {
        String key = scope.prefix + endpointId;
        String threadKey = key + ":" + Thread.currentThread().getId();
        int limit = limitFor(scope);

        try {
            RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(key);
            
            // setPermits, not trySetPermits: the latter only writes an absent key, so a changed
            // limit never reached a semaphore that traffic kept alive.
            if (initializedSemaphores.getIfPresent(key) == null) {
                semaphore.setPermits(limit);
                initializedSemaphores.put(key, Boolean.TRUE);
            }

            // waitTime 0: a refusal is a Deferral, not something to wait out. The old 100 meant 100
            // seconds (one TimeUnit for both) and pinned a pool thread per attempt. The lease frees
            // a permit orphaned by a crash.
            String permitId = semaphore.tryAcquire(0, permitLeaseSeconds, TimeUnit.SECONDS);
            if (permitId == null) {
                // A missing key also refuses: Redis runs allkeys-lru and may restart empty, and a
                // missing semaphore has zero permits. Re-assert the limit and ask once more.
                semaphore.setPermits(limit);
                permitId = semaphore.tryAcquire(0, permitLeaseSeconds, TimeUnit.SECONDS);
            }
            if (permitId != null) {
                acquiredPermits.put(threadKey, permitId);
                semaphore.expire(KEY_TTL);
                activePermits.incrementAndGet();
                concurrencyAcquired.increment();
                return true;
            }
            
            concurrencyRejectedByScope.get(scope).increment();
            log.debug("Concurrency limit reached for {} {} (max: {})", scope, endpointId, limit);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring {} permit for {}", scope, endpointId);
            return false;
        } catch (Exception e) {
            log.warn("Redis concurrency control unavailable for {} {}, using local fallback: {}",
                    scope, endpointId, e.getMessage());
            concurrencyFallback.increment();
            return tryAcquireLocal(scope, key, limit);
        }
    }

    private boolean tryAcquireLocal(Scope scope, String key, int limit) {
        AtomicInteger permits = localPermits.get(key, k -> new AtomicInteger(0));
        int current = permits.incrementAndGet();
        if (current <= limit) {
            activePermits.incrementAndGet();
            concurrencyAcquired.increment();
            return true;
        }
        permits.decrementAndGet();
        concurrencyRejectedByScope.get(scope).increment();
        log.debug("Local concurrency limit reached for {} (max: {})", key, limit);
        return false;
    }

    private boolean releaseLocal(String key) {
        AtomicInteger permits = localPermits.getIfPresent(key);
        if (permits != null && permits.get() > 0) {
            permits.decrementAndGet();
            return true;
        }
        return false;
    }

    public void release(Scope scope, UUID endpointId) {
        String key = scope.prefix + endpointId;
        String threadKey = key + ":" + Thread.currentThread().getId();

        String permitId = acquiredPermits.remove(threadKey);
        if (permitId != null) {
            try {
                RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(key);
                semaphore.release(permitId);
                activePermits.decrementAndGet();
                concurrencyReleased.increment();
            } catch (Exception e) {
                log.warn("Failed to release {} permit for {}: {}", scope, endpointId, e.getMessage());
                activePermits.decrementAndGet();
            }
        } else if (releaseLocal(key)) {
            // Only when a local permit was really held, so a duplicate release cannot push the
            // gauge negative.
            activePermits.decrementAndGet();
            concurrencyReleased.increment();
        }
    }

}
