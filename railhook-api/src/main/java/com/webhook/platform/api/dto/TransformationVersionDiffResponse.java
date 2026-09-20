package com.webhook.platform.api.dto;

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

    private String rightTemplate;

    private List<JsonDiffEntry> diffs;
}
