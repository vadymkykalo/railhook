package com.webhook.platform.api.dto;

import com.webhook.platform.common.transform.TransformationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Two versions of a transformation's template, and what changed between them")
public class TransformationVersionDiffResponse {

    private UUID transformationId;

    private Integer leftVersion;

    private Integer rightVersion;

    private Instant leftCreatedAt;

    private Instant rightCreatedAt;

    @Schema(description = "The whole left-hand template, so the caller can render both sides as well as the changes")
    private String leftTemplate;

    @Schema(description = "The whole right-hand template")
    private String rightTemplate;

    @Schema(description = "The language the left-hand version was published in", example = "TEMPLATE")
    private TransformationKind leftKind;

    @Schema(description = "The language the right-hand version was published in", example = "JAVASCRIPT")
    private TransformationKind rightKind;

    @Schema(description = "Field-by-field changes between the two templates. Empty when either side is a JAVASCRIPT version: a script has no fields to compare, so the two texts are diffed line by line instead.")
    private List<JsonDiffEntry> diffs;
}
