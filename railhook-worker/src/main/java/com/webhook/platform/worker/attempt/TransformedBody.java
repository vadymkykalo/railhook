package com.webhook.platform.worker.attempt;

import java.util.Map;

/**
 * A cancellation travels back to the {@link AttemptRunner} instead of being handled in a store,
 * because the Runner is where obligations end. {@code body} is null when cancelled.
 */
public record TransformedBody(String body, Map<String, String> headers, boolean cancelled,
        String cancelReason) {

    public TransformedBody {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static TransformedBody of(String body) {
        return new TransformedBody(body, Map.of(), false, null);
    }

    public static TransformedBody cancelled(String reason) {
        return new TransformedBody(null, Map.of(), true, reason);
    }
}
