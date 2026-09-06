package com.webhook.platform.worker.attempt;

import java.time.Instant;

/**
 * What happened when {@link AttemptStore#claim} tried to take exclusive ownership of an
 * obligation for the duration of one Attempt.
 *
 * <p>Three outcomes, and the third is the interesting one. FIFO ordering is not a separate
 * stage in the Runner: parking a Delivery behind an outstanding sequence already means
 * "the Claim was released and nothing was sent", which is exactly {@link Deferred}. The
 * Outgoing store returns it; the Incoming store never does. The Runner therefore never
 * learns the word <em>ordering</em>, and no admission gate with a no-op adapter is needed
 * to keep it from having to.
 *
 * @param <C> the store's own Claim type — opaque to the Runner
 */
public sealed interface ClaimResult<C> {

    /** The Claim is held; the Attempt may proceed. */
    record Claimed<C>(C claim, AttemptContext context) implements ClaimResult<C> {
    }

    /**
     * Somebody else owns it, or it is no longer in a claimable state. Nothing to do — not an
     * error, and specifically not something to retry: whoever holds the Claim will finish it.
     */
    record NotClaimed<C>(String reason) implements ClaimResult<C> {
    }

    /**
     * The obligation is not admissible yet and the Claim has been released without an Attempt
     * being made. The store has already stamped it to come back at {@code until}.
     *
     * <p>Distinct from a failed Attempt: nothing was sent, so the Retry Ladder does not
     * advance and the attempt count is not consumed.
     */
    record Deferred<C>(Instant until, String reason) implements ClaimResult<C> {
    }
}
