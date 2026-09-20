package com.webhook.platform.common.transform;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Everything a script is allowed to see: the Event, and the context of the one Delivery or
 * Forward being attempted.
 *
 * <p>This is the whole of the guest's world. Nothing else is reachable from inside the sandbox,
 * which is why the list is short and why growing it is a contract change rather than a tweak —
 * scripts are written against these names and they have to keep meaning the same thing.
 *
 * @param payload   the event body, as JSON text. Parsed inside the guest, never marshalled
 *                  across as host objects.
 * @param eventType the Event's type, or the incoming provider's event type
 * @param eventId   the Event's id
 * @param timestamp when the Event was announced
 * @param direction {@code OUTGOING} or {@code INCOMING}, in the vocabulary of {@code CONTEXT.md}
 * @param url       the Endpoint or Destination URL this attempt is aimed at. Readable, not
 *                  writable: letting a script choose the address would put it past the SSRF
 *                  checks the Runner makes before it ever gets here.
 * @param headers   the request headers computed so far, signature excluded
 * @param attemptNumber which try this is, 1 for the first. A script that adds an idempotency
 *                  hint, or logs differently on a retry, needs it and cannot work it out. Null
 *                  in a preview, where there is no Attempt.
 */
@Builder
public record TransformRequest(
        String payload,
        String eventType,
        String eventId,
        Instant timestamp,
        String direction,
        String url,
        Map<String, String> headers,
        Integer attemptNumber) {

    public TransformRequest {
        payload = payload == null || payload.isBlank() ? "null" : payload;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
