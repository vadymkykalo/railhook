package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Runs a sweep on one replica at a time, without waiting for the lock and without requiring it.
 * The sweeps are conditional UPDATEs, so two replicas running at once only waste a query. They
 * are also the only thing that revokes a lost Claim, and a Redis outage is exactly when workers
 * restart and lose Claims, so gating them on Redis made the outage block its own recovery.
 */
@Slf4j
@Component
public class ExclusiveSweep {

    private static final long LEASE_SECONDS = 30;

    private final RedissonClient redissonClient;
    private final Counter degraded;

    public ExclusiveSweep(RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.degraded = Counter.builder("sweep_degraded_total")
                .description("Sweeps that ran without the exclusivity lock because Redis was unreachable")
                .register(meterRegistry);
    }

    public void run(String lockKey, String what, Runnable body) {
        RLock lock;
        boolean acquired;
        try {
            lock = redissonClient.getLock(lockKey);
            acquired = lock.tryLock(0, LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring the {} lock", what);
            return;
        } catch (Exception e) {
            degraded.increment();
            log.warn("{}: Redis unavailable ({}), sweeping without the exclusivity lock — "
                    + "another replica may sweep at the same time, which is idempotent", what, e.getMessage());
            runQuietly(what, body);
            return;
        }

        if (!acquired) {
            log.debug("{} already running on another instance, skipping", what);
            return;
        }

        try {
            runQuietly(what, body);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.debug("{}: could not release the lock: {}", what, e.getMessage());
            }
        }
    }

    private void runQuietly(String what, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            log.error("{} failed: {}", what, e.getMessage(), e);
        }
    }
}
