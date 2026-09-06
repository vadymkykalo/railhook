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
     * What a permit is counted against.
     *
     * <p>Two caps, because one of them was the wrong shape on its own. {@code TARGET} bounds how
     * many attempts one receiver can have in flight, which stops a single slow endpoint taking
     * the pool. It does nothing about a tenant with twenty slow endpoints: each stays inside its
     * own slice and their sum is the whole worker, so every other organization stops being
     * delivered to. {@code TENANT} is the cap on that sum.
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
    // Keyed by scope + id, not id alone: the same worker holds a tenant permit and a target
    // permit at once, and they are different budgets.
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
    // Rejections are split by scope: "the whole organization is at its ceiling" and "this one
    // receiver is" are different operational facts. Without the tag an operator cannot tell
    // whether the tenant cap is protecting the other tenants or throttling the only one.
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

    /** Bounds one organization's total in-flight attempts across every one of its targets. */
    public boolean tryAcquireForTenant(UUID tenantId) {
        return tryAcquire(Scope.TENANT, tenantId);
    }

    /** Bounds in-flight attempts to one receiver. */
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
            
            if (initializedSemaphores.getIfPresent(key) == null) {
                semaphore.trySetPermits(limit);
                initializedSemaphores.put(key, Boolean.TRUE);
            }
            
            // leaseTime bounds how long a permit can be held without release() being called.
            // Without it an orphaned permit (crashed pod, or a code path that throws before
            // the caller's finally) never comes back until the whole semaphore key's 24h TTL
            // lapses, and that TTL only refreshes on a successful acquire — so an exhausted
            // semaphore self-heals here even if the caller-side release is ever skipped again.
            // waitTime 0: check and return, never block. Redisson's signature is
            // tryAcquire(waitTime, leaseTime, unit) — one TimeUnit governs both — so the 100
            // that used to sit here meant 100 *seconds* of waiting, not the 100 milliseconds
            // it reads as. AttemptRunner.admit() treats this the way it treats the rate
            // limiters and the breaker: a refusal is a Deferral, not something to wait out.
            // Waiting instead pinned a BoundedAsyncExecutor thread per attempt, so a single
            // saturated endpoint drained the outgoing pool, BoundedAsyncExecutor paused every
            // Kafka container, and the worker stopped delivering for every tenant. The wait
            // also outlasted the lease below, so a permit expired while still in use and the
            // cap this class exists to enforce quietly stopped holding.
            String permitId = semaphore.tryAcquire(0, permitLeaseSeconds, TimeUnit.SECONDS);
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
            // Only counted here if a local-fallback permit was actually held — otherwise
            // this is a release() call with no matching acquire (e.g. a duplicate release,
            // or a path with no permit to begin with) and must not drag the gauge negative.
            activePermits.decrementAndGet();
            concurrencyReleased.increment();
        }
    }

}
