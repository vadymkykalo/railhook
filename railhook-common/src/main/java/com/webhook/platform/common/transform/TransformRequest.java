package com.webhook.platform.common.transform;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Everything a script can see. Scripts are written against these names, so adding or changing
 * one is a contract change.
 *
 * <p>{@code url} is read-only because the SSRF checks have already run. {@code headers} are the
 * configured ones only: signatures and ids are computed after the script, so a script cannot leak
 * them. {@code attemptNumber} is null in a preview.
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
