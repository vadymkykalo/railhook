package com.webhook.platform.common.transform;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/** Exactly one of {@code payload} and {@code cancelled} is set; anything else threw earlier. */
@Builder
public record TransformOutcome(
        String payload,
        Map<String, String> headers,
        boolean cancelled,
        String cancelReason,
        List<ScriptConsoleLine> console,
        boolean consoleTruncated,
        long durationMs) {

    public TransformOutcome {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        console = console == null ? List.of() : List.copyOf(console);
    }
}
