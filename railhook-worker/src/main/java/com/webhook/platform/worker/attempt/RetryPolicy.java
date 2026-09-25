package com.webhook.platform.worker.attempt;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Backoff for a Deferral only. The Ladder and the retryable statuses are separate, and mixing
 * them up is how a deferred delivery ends up consuming its Ladder.
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    public static long backoffWithJitter(int attempt, long baseSeconds, long maxSeconds) {
        long delay = Math.min(baseSeconds * (1L << Math.min(attempt, 10)), maxSeconds);
        long jitter = (long) (delay * 0.25);
        return delay - jitter + ThreadLocalRandom.current().nextLong(2 * jitter + 1);
    }
}
