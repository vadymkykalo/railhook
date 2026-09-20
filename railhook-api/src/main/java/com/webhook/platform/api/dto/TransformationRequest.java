package com.webhook.platform.api.dto;

import com.webhook.platform.common.transform.TransformationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create or update a transformation")
public class TransformationRequest {

    @Schema(description = "Human-readable name for the transformation", example = "Stripe v2 → CRM v1")
    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be at most 255 characters")
    private String name;

    @Schema(description = "Description of what this transformation does", example = "Normalizes Stripe payment events to CRM format")
    @Size(max = 4096, message = "Description must be at most 4096 characters")
    private String description;

    @Schema(description = "The transformation itself: a JSON template with ${$.jsonpath} expressions when kind is TEMPLATE, or a JavaScript `function handler(webhook) { ... }` when kind is JAVASCRIPT",
            example = "{\"event_type\": \"${$.type}\", \"amount\": \"${$.data.amount}\"}")
    @NotBlank(message = "Template is required")
    @Size(max = 65536, message = "Template must be at most 65536 characters")
    private String template;

    @Schema(description = "The language the template is written in. Omitted means TEMPLATE, which is what every transformation was before JavaScript existed.",
            example = "TEMPLATE")
    private TransformationKind kind;

    @Schema(description = "Whether this transformation is active", example = "true")
    private Boolean enabled;
}
