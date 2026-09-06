package com.webhook.platform.worker.service;

import lombok.RequiredArgsConstructor;
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
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ExclusiveSweep {

    private static final long LEASE_SECONDS = 30;

    private final RedissonClient redissonClient;

    public void run(String lockKey, String what, Runnable body) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("{} already running on another instance, skipping", what);
                return;
            }
            body.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring the {} lock", what);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
