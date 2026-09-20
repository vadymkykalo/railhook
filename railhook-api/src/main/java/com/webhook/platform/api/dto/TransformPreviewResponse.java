package com.webhook.platform.api.dto;

import com.webhook.platform.common.transform.ScriptTransformException;
import com.webhook.platform.common.transform.TransformationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "What the transformation produced, and what it said while producing it")
public class TransformPreviewResponse {

    @Schema(description = "The body that would be sent, pretty-printed. Null when the run failed or the script cancelled the delivery.")
    private String outputPayload;

    @Schema(description = "The headers that would be sent, as a JSON object: the ones you supplied, with anything the script set merged over them")
    private String outputHeaders;

    @Schema(description = "Whether the run produced a payload")
    private boolean success;

    @Schema(description = "What went wrong, in the order it went wrong")
    private List<String> errors;

    @Schema(description = "The language that actually ran")
    private TransformationKind kind;

    @Schema(description = "Everything the script logged, oldest first. Always empty for a template, which has no console.")
    private List<ConsoleLine> console;

    @Schema(description = "True when the script logged more lines than the deployment keeps")
    private boolean consoleTruncated;

    @Schema(description = "True when the script asked for the delivery to be dropped")
    private boolean cancelled;

    @Schema(description = "Why the script cancelled, as the script stated it")
    private String cancelReason;

    @Schema(description = "Wall clock spent inside the script, in milliseconds")
    private long durationMs;

    @Schema(description = "The line in the script the failure came from, 1-based, or null when the failure has no location")
    private Integer errorLine;

    @Schema(description = "Why the run produced nothing, as a value rather than as prose: SYNTAX, CONTRACT, RUNTIME, TIMEOUT, MEMORY, OUTPUT_TOO_LARGE, SOURCE_TOO_LARGE or UNAVAILABLE. Null when the run succeeded. A client shows its own wording for these; `errors` carries the engine's, in English.",
            example = "TIMEOUT")
    private ScriptTransformException.Reason errorReason;

    /** One {@code console.*} call. Mirrors {@code ScriptConsoleLine} so the wire shape is ours. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One console.* call a script made")
    public static class ConsoleLine {

        @Schema(description = "log, info, warn, error or debug", example = "log")
        private String level;

        @Schema(description = "The arguments, already formatted")
        private String message;
    }
}
