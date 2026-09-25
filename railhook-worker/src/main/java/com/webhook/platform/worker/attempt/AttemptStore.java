package com.webhook.platform.worker.attempt;

/**
 * One adapter per direction: how a Claim is taken, proved and released, and how Attempts are
 * recorded. The Runner is generic over the Claim type {@code C}, so it cannot read a fencing
 * token, only hand the Claim back.
 */
public interface AttemptStore<C> {

    /** Admissibility checks live here, so the Runner knows nothing of ordering. */
    ClaimResult<C> claim();

    /** Takes the transformed body so the signature covers exactly the bytes sent. */
    RequestSpec buildRequest(C claim, TransformedBody transformed);

    /** Throws PayloadTransformException rather than ever return the untransformed payload. */
    TransformedBody buildBody(C claim);

    /** A store that kept a body's exact received bytes returns those instead. */
    default byte[] wireBody(C claim, String body) {
        return body != null ? body.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
    }

    /** Outgoing spends the rung here, so a crash mid-send still counts against the Ladder. */
    default void attemptStarting(C claim) {
    }

    /** Also called for Attempts that never reached the network, so a refused URL leaves a trace. */
    void recordAttempt(C claim, AttemptRecord record);

    /**
     * Writes the outcome only while this Claim still owns the row. Returns false if the row was
     * reclaimed or already terminal; the Runner then queues no successor, which stops a late
     * writer from delivering a succeeded webhook twice.
     */
    boolean finalise(C claim, Finalization outcome);

    /** Outside the finalising transaction, so a Kafka failure cannot roll back the DLQ write. */
    void onAbandoned(C claim);

    void onSucceeded(C claim);

    /**
     * Called once per Attempt that reached a verdict, never for a Deferral: counting our own
     * throttling against a target would auto-disable it for being busy. Kept apart from the
     * circuit breaker, which forgets in minutes; auto-disabling needs a memory that outlives the
     * worker. The Runner swallows anything this throws (invariant 1).
     */
    default void recordTargetOutcome(C claim, boolean succeeded) {
    }

    /** Must release what Succeeded and Abandoned release, or the ordering cursor stalls. */
    default void onTerminallyFailed(C claim) {
    }

    default void onCancelled(C claim) {
        onTerminallyFailed(claim);
    }
}
