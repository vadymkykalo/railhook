package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive rate governor for retry schedulers.
 * Prevents retry storms via AIMD (Additive Increase, Multiplicative Decrease)
 * congestion control + queue-depth-based admission control.
 *
 * <p>Usage: call {@link #computeEffectiveBatch(long)} before each poll to get the
 * adaptive batch size, then call {@link #recordResult(int, int)} after the poll
 * to feed back success/failure counts.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>AIMD batch sizing</b> — on success: +increment; on failure (>50%): halve.
 *       Floor = {@code minBatch}, ceiling = {@code maxBatch}.</li>
 *   <li><b>Queue depth governor</b> — when pending count exceeds {@code highWatermark},
 *       effective batch is capped proportionally to prevent burst draining.</li>
 *   <li><b>Consecutive failure cooldown</b> — after N consecutive bad polls the governor
 *       returns 0 (skip poll) with exponential backoff up to {@code maxCooldownPolls}.</li>
 * </ol>
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

    /**
     * @param name            identifier for logging/metrics (e.g. "outgoing", "incoming-forward")
     * @param maxBatch        configured maximum batch size (upper ceiling)
     * @param minBatch        minimum batch size (floor, never below 1)
     * @param increment       additive increase step per successful poll
     * @param highWatermark   pending count above which admission control kicks in
     * @param maxCooldownPolls max consecutive polls to skip on sustained failures
     * @param meterRegistry   for publishing governor gauges
     */
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

    /**
     * @param pendingCount pending retries, or -1 when unknown, which skips the depth governor
     * @return batch size; 0 means skip this poll
     */
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
            // At most highWatermark/10 per poll, draining over ~100 polls.
            int depthCap = Math.max(minBatch, (int) (highWatermark / 10));
            if (batch > depthCap) {
                log.info("[{}] Queue depth governor: pending={} > highWatermark={}, capping batch {} → {}",
                        name, pendingCount, highWatermark, batch, depthCap);
                batch = depthCap;
            }
        }

        return batch;
    }

    /**
     * Records the outcome of a poll and adjusts the AIMD window.
     *
     * @param dispatched number of successfully dispatched items
     * @param failed     number of items that failed to dispatch (Kafka send failures, timeouts)
     */
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
            // "{:.1f}" is Python, not SLF4J. SLF4J only substitutes "{}", so that token printed
            // literally, every argument after it landed one placeholder early - the failure rate
            // was rendered where the batch size belongs - and the last one was dropped with no
            // warning. The line said "batch 94.44444444444444 → 18" about a batch that was 18.
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
     * Poll interval for the next cycle: aggressive under backlog, backing off when empty.
     *
     * <p>A multiple of the caller's configured interval, not an absolute constant. Hardcoded
     * values here used to override {@code retry.scheduler.poll-interval-ms} from the first poll
     * onward, so tuning the setting did nothing.
     */
    public long getRecommendedPollIntervalMs(long pendingCount, long basePollIntervalMs) {
        long base = Math.max(1, basePollIntervalMs);
        long interval;
        if (pendingCount < 0) {
            interval = base; // Unknown queue depth, use the configured interval
        } else if (pendingCount == 0) {
            interval = base * 3; // Empty queue, back off (30s at the 10s default)
        } else if (pendingCount < 100) {
            interval = base; // Light load (10s at the 10s default)
        } else if (pendingCount < 1000) {
            interval = Math.max(1, base / 2); // Medium load (5s at the 10s default)
        } else {
            interval = Math.max(1, base / 5); // Heavy backlog, aggressive (2s at the 10s default)
        }
        recommendedPollIntervalMs.set(interval);
        return interval;
    }
}
