package com.webhook.platform.worker.service;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The polling half of a retry scheduler: its own daemon thread, a startup jitter so replicas do
 * not poll in step, and a cadence the {@link RetryGovernor} sets from how deep the backlog is.
 *
 * <p>A poll that throws is logged and the next one is still scheduled — a loop that stops on the
 * first failure is a retry ladder that stops with it.
 */
@Slf4j
class AdaptivePollLoop {

    private static final long STARTUP_JITTER_MS = 5000;
    private static final long SHUTDOWN_GRACE_SECONDS = 10;

    private final String name;
    private final RetryGovernor governor;
    private final long defaultPollIntervalMs;
    private final LongSupplier pendingCount;
    private final LongConsumer sweep;
    private final ScheduledExecutorService scheduler;

    AdaptivePollLoop(String name, RetryGovernor governor, long defaultPollIntervalMs,
            LongSupplier pendingCount, LongConsumer sweep) {
        this.name = name;
        this.governor = governor;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
        this.pendingCount = pendingCount;
        this.sweep = sweep;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    void start() {
        long startupJitter = ThreadLocalRandom.current().nextLong(0, STARTUP_JITTER_MS);
        log.info("{} starting with {}ms jitter, default poll interval {}ms",
                name, startupJitter, defaultPollIntervalMs);
        scheduler.schedule(this::pollAndReschedule, startupJitter, TimeUnit.MILLISECONDS);
    }

    void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void pollAndReschedule() {
        long nextDelay = defaultPollIntervalMs;
        try {
            long pending = pendingCount.getAsLong();
            sweep.accept(pending);
            nextDelay = governor.getRecommendedPollIntervalMs(pending, defaultPollIntervalMs);
        } catch (Exception e) {
            log.error("{} poll failed: {}", name, e.getMessage(), e);
        } finally {
            scheduler.schedule(this::pollAndReschedule, nextDelay, TimeUnit.MILLISECONDS);
        }
    }
}
