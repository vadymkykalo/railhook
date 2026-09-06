package com.webhook.platform.worker.attempt;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Attempt-level policy that is not the retry ladder: which HTTP statuses are worth another
 * attempt, and how long to wait when an attempt is deferred rather than made. The ladder itself
 * lives in {@link com.webhook.platform.common.retry.RetryLadder}.
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    public static boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    /**
     * Backoff for a deferral — an attempt turned away before it was made — as opposed to the
     * ladder, which governs attempts that were made and failed. Both directions share this.
     */
    public static long backoffWithJitter(int attempt, long baseSeconds, long maxSeconds) {
        long delay = Math.min(baseSeconds * (1L << Math.min(attempt, 10)), maxSeconds);
        long jitter = (long) (delay * 0.25);
        return delay - jitter + ThreadLocalRandom.current().nextLong(2 * jitter + 1);
    }
}
