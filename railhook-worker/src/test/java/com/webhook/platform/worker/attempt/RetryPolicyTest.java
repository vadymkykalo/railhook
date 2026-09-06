package com.webhook.platform.worker.attempt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what is left in RetryPolicy once the retry ladder moved out: which HTTP statuses
 * are worth another attempt, and the deferral backoff used when an attempt is turned away
 * by a rate limit, a concurrency cap or an open circuit breaker.
 *
 * <p>The ladder's own arithmetic — parsing, tier clamping, jitter, exhaustion, hard-cap fit
 * — is covered by {@code RetryLadderTest} in the common module. Those cases are not
 * duplicated here: they used to assert that a malformed ladder falls back to a hardcoded
 * default, which is the behaviour {@code RetryLadder} exists to remove.
 */
class RetryPolicyTest {

    // --- isRetryable -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = { 408, 429, 500, 502, 503, 504, 599 })
    void isRetryable_trueForTimeoutRateLimitAnd5xx(int status) {
        assertTrue(RetryPolicy.isRetryable(status));
    }

    @ParameterizedTest
    @ValueSource(ints = { 200, 201, 301, 400, 401, 403, 404, 409, 422, 600, 999 })
    void isRetryable_falseForEverythingElse(int status) {
        assertFalse(RetryPolicy.isRetryable(status));
    }

    // --- backoffWithJitter --------------------------------------------------------------

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
