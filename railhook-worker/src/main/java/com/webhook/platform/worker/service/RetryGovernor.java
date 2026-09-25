package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AIMD batch sizing for a retry scheduler: add {@code increment} after a good poll, halve after
 * one where more than half failed. Above {@code highWatermark} pending the batch is capped so a
 * backlog drains gradually, and three bad polls in a row start an exponential cooldown.
 */
@Slf4j
public class RetryGovernor {

    private final int maxBatch;
    private final int minBatch;
    private final int increment;
    private final long highWatermark;
    private final int maxCooldownPolls;
    private final String name;

    private final AtomicInteger effectiveBatch;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger cooldownRemaining = new AtomicInteger(0);
    private final AtomicLong lastPendingCount = new AtomicLong(0);
    private final AtomicLong recommendedPollIntervalMs = new AtomicLong(10_000);

    public RetryGovernor(String name, int maxBatch, int minBatch, int increment,
                         long highWatermark, int maxCooldownPolls, MeterRegistry meterRegistry) {
        this.name = name;
        this.maxBatch = maxBatch;
        this.minBatch = Math.max(1, minBatch);
        this.increment = Math.max(1, increment);
        this.highWatermark = highWatermark;
        this.maxCooldownPolls = maxCooldownPolls;
        this.effectiveBatch = new AtomicInteger(maxBatch);

        Gauge.builder("retry_governor_effective_batch", effectiveBatch, AtomicInteger::doubleValue)
                .tag("scheduler", name).register(meterRegistry);
        Gauge.builder("retry_governor_consecutive_failures", consecutiveFailures, AtomicInteger::doubleValue)
                .tag("scheduler", name).register(meterRegistry);
        Gauge.builder("retry_governor_cooldown_remaining", cooldownRemaining, AtomicInteger::doubleValue)
                .tag("scheduler", name).register(meterRegistry);
        Gauge.builder("retry_governor_pending_count", lastPendingCount, AtomicLong::doubleValue)
                .tag("scheduler", name).register(meterRegistry);
        Gauge.builder("retry_governor_recommended_poll_interval_ms", recommendedPollIntervalMs, AtomicLong::doubleValue)
                .tag("scheduler", name).register(meterRegistry);
    }

    /** Returns 0 to skip this poll. A pendingCount of -1 means unknown and skips the depth cap. */
    public int computeEffectiveBatch(long pendingCount) {
        if (pendingCount >= 0) {
            lastPendingCount.set(pendingCount);
        }

        int cd = cooldownRemaining.get();
        if (cd > 0) {
            cooldownRemaining.decrementAndGet();
            log.info("[{}] Governor cooldown: skipping poll ({} remaining)", name, cd - 1);
            return 0;
        }

        int batch = effectiveBatch.get();

        if (pendingCount > highWatermark && highWatermark > 0) {
            int depthCap = Math.max(minBatch, (int) (highWatermark / 10));
            if (batch > depthCap) {
                log.info("[{}] Queue depth governor: pending={} > highWatermark={}, capping batch {} → {}",
                        name, pendingCount, highWatermark, batch, depthCap);
                batch = depthCap;
            }
        }

        return batch;
    }

    public void recordResult(int dispatched, int failed) {
        int total = dispatched + failed;
        if (total == 0) {
            effectiveBatch.set(maxBatch);
            consecutiveFailures.set(0);
            return;
        }

        double failureRate = (double) failed / total;

        if (failureRate > 0.5) {
            int current = effectiveBatch.get();
            int newBatch = Math.max(minBatch, current / 2);
            effectiveBatch.set(newBatch);

            int cf = consecutiveFailures.incrementAndGet();
            // SLF4J only substitutes "{}"; a Python-style "{:.1f}" here once shifted every argument.
            log.warn("[{}] AIMD decrease: failureRate={}%, batch {} → {}, consecutiveFailures={}",
                    name, String.format("%.1f", failureRate * 100), current, newBatch, cf);

            if (cf >= 3) {
                int cooldown = Math.min(maxCooldownPolls, 1 << (cf - 3));
                cooldownRemaining.set(cooldown);
                log.warn("[{}] Entering cooldown for {} polls after {} consecutive failures",
                        name, cooldown, cf);
            }
        } else {
            int current = effectiveBatch.get();
            int newBatch = Math.min(maxBatch, current + increment);
            effectiveBatch.set(newBatch);
            consecutiveFailures.set(0);

            if (newBatch != current) {
                log.debug("[{}] AIMD increase: batch {} → {}", name, current, newBatch);
            }
        }
    }

    public int getEffectiveBatch() {
        return effectiveBatch.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int getCooldownRemaining() {
        return cooldownRemaining.get();
    }

    /**
     * A multiple of the configured interval, never an absolute value: hardcoded values once
     * overrode {@code retry.scheduler.poll-interval-ms}, so tuning it did nothing.
     */
    public long getRecommendedPollIntervalMs(long pendingCount, long basePollIntervalMs) {
        long base = Math.max(1, basePollIntervalMs);
        long interval;
        if (pendingCount < 0) {
            interval = base;
        } else if (pendingCount == 0) {
            interval = base * 3;
        } else if (pendingCount < 100) {
            interval = base;
        } else if (pendingCount < 1000) {
            interval = Math.max(1, base / 2);
        } else {
            interval = Math.max(1, base / 5);
        }
        recommendedPollIntervalMs.set(interval);
        return interval;
    }
}
