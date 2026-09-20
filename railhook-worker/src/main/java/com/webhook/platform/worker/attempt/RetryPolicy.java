package com.webhook.platform.worker.attempt;

import java.util.concurrent.ThreadLocalRandom;

/**
 * How long to wait when an Attempt is <em>deferred</em> rather than made — and nothing else.
 *
 * <p>It used to also answer "is this status worth another Attempt", as three literals. That
 * answer is the obligation's own now and travels on {@link AttemptContext#retryableStatuses()};
 * see {@link com.webhook.platform.common.retry.RetryableStatuses}. The Ladder — when the next
 * Attempt is due — lives in {@link com.webhook.platform.common.retry.RetryLadder}. Three
 * different questions, three homes, and conflating any two of them is how a deferred delivery
 * ends up consuming its Ladder.
 */
public final class RetryPolicy {

    private RetryPolicy() {
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
