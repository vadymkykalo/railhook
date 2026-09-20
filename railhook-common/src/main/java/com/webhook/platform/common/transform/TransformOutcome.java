package com.webhook.platform.common.transform;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * What one run of a script produced.
 *
 * <p>Either {@code payload} is set or {@code cancelled} is true — never both, never neither. A
 * run that produced neither is a contract failure and threw before this record existed.
 *
 * @param payload          the body to send, as JSON text; null when cancelled
 * @param headers          headers the script added or overrode, already stringified
 * @param cancelled        true when the script asked for the delivery to be dropped
 * @param cancelReason     why, as the script stated it; may be null
 * @param console          what the script logged, oldest first, capped by {@link ScriptLimits}
 * @param consoleTruncated true when the script logged more than the cap allowed
 * @param durationMs       wall clock spent inside the guest
 */
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
