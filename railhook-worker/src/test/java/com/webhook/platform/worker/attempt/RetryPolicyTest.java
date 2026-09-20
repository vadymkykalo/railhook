package com.webhook.platform.worker.attempt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the one thing left in RetryPolicy: the deferral backoff, used when an Attempt is
 * turned away by a rate limit, a concurrency cap or an open circuit breaker.
 *
 * <p>The ladder's arithmetic is {@code RetryLadderTest}'s, and which statuses are worth another
 * Attempt is {@code RetryableStatusesTest}'s — both in the common module, because both are now
 * carried by the obligation rather than decided here.
 */
class RetryPolicyTest {

    @Test
    void backoffWithJitter_staysWithinComputedJitterBounds_acrossManyRuns() {
        long baseSeconds = 2;
        long maxSeconds = 60;
        for (int attempt = 0; attempt < 15; attempt++) {
            long delay = Math.min(baseSeconds * (1L << Math.min(attempt, 10)), maxSeconds);
            long jitter = (long) (delay * 0.25);
            for (int i = 0; i < 50; i++) {
                long actual = RetryPolicy.backoffWithJitter(attempt, baseSeconds, maxSeconds);
                assertTrue(actual >= delay - jitter && actual <= delay + jitter,
                        "attempt=" + attempt + " expected within [" + (delay - jitter) + "," + (delay + jitter)
                                + "] but was " + actual);
            }
        }
    }

    @Test
    void backoffWithJitter_neverExceedsMaxByMoreThanJitterMargin() {
        // At high attempt counts the exponential term saturates the cap; the returned
        // value can exceed maxSeconds by up to 25% due to jitter, but never more.
        long maxSeconds = 60;
        for (int i = 0; i < 100; i++) {
            long actual = RetryPolicy.backoffWithJitter(20, 2, maxSeconds);
            assertTrue(actual <= (long) (maxSeconds * 1.25) + 1,
                    "capped delay's jitter must not blow past +25%, was " + actual);
        }
    }
}
