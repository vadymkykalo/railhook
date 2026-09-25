package com.webhook.platform.worker.attempt;

import java.time.Instant;

/**
 * FIFO parking is expressed as {@link Deferred}, so the Runner has no notion of ordering.
 */
public sealed interface ClaimResult<C> {

    record Claimed<C>(C claim, AttemptContext context) implements ClaimResult<C> {
    }

    /** Not an error and not to be retried: whoever holds the Claim will finish it. */
    record NotClaimed<C>(String reason) implements ClaimResult<C> {
    }

    /** Released unattempted and already stamped to return at {@code until}. Spends no rung. */
    record Deferred<C>(Instant until, String reason) implements ClaimResult<C> {
    }
}
