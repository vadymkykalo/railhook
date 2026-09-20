package com.webhook.platform.worker.attempt;

/**
 * How one direction records its Attempts, and how a Claim on an obligation is taken, proved
 * and released. One adapter per direction.
 *
 * <p>{@code C} is the store's own Claim type and {@link AttemptRunner} is generic over it, so
 * the Runner cannot read a fencing token: it receives a {@code C} from {@link #claim} and can
 * do nothing with it but hand it back.
 *
 * @param <C> the store's Claim type, opaque to the Runner
 */
public interface AttemptStore<C> {

    /**
     * Take exclusive ownership for the duration of one Attempt.
     *
     * <p>Admissibility checks live here — the Outgoing FIFO gate comes back as
     * {@link ClaimResult.Deferred} — so the Runner has no ordering concept. An implementation
     * may also finalise the obligation terminally and return {@link ClaimResult.NotClaimed}.
     */
    ClaimResult<C> claim();

    /**
     * Build the request for this Attempt: signing, auth, client selection, headers.
     *
     * <p>Takes the already-transformed body so Outgoing's signature is computed over exactly the
     * bytes that go out, and so a transformation that set a header can have it applied here —
     * over Railhook's own, under the target's configured custom headers.
     */
    RequestSpec buildRequest(C claim, TransformedBody transformed);

    /**
     * The body to send, transformed. What to do when the transformation fails is the Runner's
     * decision, not the adapter's — and so is what to do when it cancels, which is why a
     * cancellation comes back in the return rather than as an exception.
     *
     * @throws com.webhook.platform.worker.service.PayloadTransformException when a configured
     *         transformation cannot be applied. Never return the untransformed payload.
     */
    TransformedBody buildBody(C claim);

    /**
     * The bytes that go on the wire for {@code body}, which is what {@link #buildBody} returned.
     *
     * <p>The body is recorded and signed as text, but it is sent as bytes, so nothing between the
     * store and the socket re-encodes it or adds a charset to the Content-Type. The default is the
     * text's UTF-8 encoding; a store holding the exact bytes a body arrived with hands those back
     * instead.
     */
    default byte[] wireBody(C claim, String body) {
        return body != null ? body.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
    }

    /**
     * Called immediately before the request goes out. Outgoing consumes an attempt here, so a
     * crash mid-send still counts against the Ladder; Incoming's row already carries its number.
     */
    default void attemptStarting(C claim) {
    }

    /**
     * Persist what this Attempt did. Always called before {@link #finalise}, and called even
     * for Attempts that never reached the network, so a refused URL leaves a trace.
     */
    void recordAttempt(C claim, AttemptRecord record);

    /**
     * Write the outcome, but only while this Claim still owns the row.
     *
     * @return true if the write applied; false if the row was reclaimed or already terminal.
     *         The Runner creates no successor Attempt unless it applied, which is what stops a
     *         late writer queueing a second delivery of a webhook that already succeeded.
     */
    boolean finalise(C claim, Finalization outcome);

    /**
     * Called once after an {@link Finalization.Abandoned} that applied. Deliberately outside
     * the finalising transaction: a Kafka or Redis failure here must not roll back a committed
     * DLQ write.
     */
    void onAbandoned(C claim);

    /** Called once after a {@link Finalization.Succeeded} that applied, for the same reason. */
    void onSucceeded(C claim);

    /**
     * What this Attempt says about the <em>target</em> — the Endpoint or the Destination —
     * rather than about the obligation. Called once per Attempt that reached a verdict, and
     * never for a {@link Finalization.Deferred}: a Deferral is not an Attempt, so the target
     * said nothing, and counting our own throttling against it would disable a target for
     * being busy.
     *
     * <p>Separate from the circuit breaker on purpose. The breaker forgets within a couple of
     * minutes, which is what makes it safe to trip; auto-disabling a target that has answered
     * nothing but failures for days needs a memory that outlives a worker, and that means a
     * row. The store owns which row and how it is written.
     *
     * <p>Whatever this throws is swallowed by the Runner. Invariant 1 binds here as anywhere
     * else: once a 2xx is in hand, nothing that goes wrong writing it down may reclassify it.
     *
     * @param succeeded true for a 2xx; false for any other answer, and for an Attempt that
     *                  produced no answer at all
     */
    default void recordTargetOutcome(C claim, boolean succeeded) {
    }

    /**
     * Called once after a {@link Finalization.TerminallyFailed} that applied. Terminal is as
     * final as Succeeded and Abandoned, so whatever those release has to be released here too —
     * a held ordering cursor stalls every later Delivery to that endpoint, silently.
     */
    default void onTerminallyFailed(C claim) {
    }
}
