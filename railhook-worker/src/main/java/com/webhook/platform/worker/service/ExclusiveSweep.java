package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Runs a periodic sweep on one replica at a time.
 *
 * <p>Does not wait for the lock: a sweep another replica is already running is a sweep this one
 * does not need to run. The lease expires on its own, so a replica that dies mid-sweep does not
 * block the next one.
 *
 * <p>And does not require the lock. What the lock buys is that one replica sweeps rather than
 * several; the sweeps behind it are {@code UPDATE ... WHERE status = 'PROCESSING' AND
 * last_attempt_at &lt; threshold}, so a second replica running one concurrently finds nothing left
 * to claim. Losing that coordination costs a wasted query. Losing the sweep costs every Delivery
 * whose worker died: these are the only things that revoke a lost Claim, and a Redis outage is
 * precisely when workers are being restarted and Claims are being lost — so gating them on Redis
 * made the outage the reason the recovery from it did not happen. The breaker fails open for the
 * same reason and is counted rather than silent; so is this.
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
                // The lease expires on its own; a Redis failure here is not worth failing over.
                log.debug("{}: could not release the lock: {}", what, e.getMessage());
            }
        }
    }

    /**
     * A sweep that throws must not take the scheduler's thread with it — it runs again next tick,
     * and the exception escaping told nobody anything the log does not.
     */
    private void runQuietly(String what, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            log.error("{} failed: {}", what, e.getMessage(), e);
        }
    }
}
