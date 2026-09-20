package com.webhook.platform.api.dto;

import com.webhook.platform.common.transform.TransformationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Run a transformation against a sample payload without saving or sending anything")
public class TransformPreviewRequest {

    @Schema(description = "The event payload to transform")
    @NotBlank
    private String inputPayload;

    @Schema(description = "A bare JSONPath to extract, for the simplest case. Ignored when a template, script or transformation is given.")
    private String transformExpression;

    @Schema(description = "An unsaved template or script to run. This is what the Transform Studio sends while you are still editing.")
    private String template;

    @Schema(description = "The language `template` is written in. Omitted means TEMPLATE.")
    private TransformationKind kind;

    @Schema(description = "A saved transformation to run instead. Takes priority over `template`, and brings its own kind.")
    private UUID transformationId;

    @Schema(description = "Headers to merge, as a JSON object. A script sees these as `webhook.headers` and may override them.")
    private String customHeaders;

    @Schema(description = "The event type a script sees as `webhook.eventType`", example = "order.completed")
    private String eventType;

    @Schema(description = "The event id a script sees as `webhook.eventId`")
    private String eventId;

    @Schema(description = "The destination URL a script sees as `webhook.url`. Read-only to the script; a transformation cannot redirect a delivery.")
    private String url;
}
