package com.webhook.platform.worker.attempt;

import java.util.Map;

/**
 * What a Transformation produced for one Attempt.
 *
 * <p>It exists because a JavaScript transformation can do two things a template never could: set
 * a header, and say "do not send this at all". Both of those are decisions the
 * {@link AttemptRunner} has to make — a cancellation ends the obligation, and the Runner is
 * where obligations end — so they travel out of {@link AttemptStore#buildBody} rather than being
 * quietly applied inside one direction's store.
 *
 * @param body         the bytes to send, as text. Null when {@code cancelled}.
 * @param headers      headers the transformation set. Applied over Railhook's own and under the
 *                     target's configured custom headers, which stay the last word.
 * @param cancelled    true when the transformation asked for this Delivery or Forward to be
 *                     dropped. Not a failure: nothing is retried and nothing reaches the DLQ.
 * @param cancelReason why, as the script stated it
 */
public record TransformedBody(String body, Map<String, String> headers, boolean cancelled,
        String cancelReason) {

    public TransformedBody {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** The ordinary case: a body and nothing else to say about it. */
    public static TransformedBody of(String body) {
        return new TransformedBody(body, Map.of(), false, null);
    }

    public static TransformedBody cancelled(String reason) {
        return new TransformedBody(null, Map.of(), true, reason);
    }
}
