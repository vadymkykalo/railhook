package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to dry-run a delivery: shows what the endpoint would receive without actually sending")
public class DeliveryDryRunRequest {

    @Schema(description = "The event payload JSON to transform", example = "{\"type\": \"order.created\", \"data\": {\"amount\": 100}}")
    @NotBlank(message = "Payload is required")
    private String payload;

    @Schema(description = "ID of a saved Transformation to apply (highest priority)")
    private UUID transformationId;

    @Schema(description = "Inline JSON template with ${$.path} expressions")
    private String payloadTemplate;

    @Schema(description = "Custom headers JSON to merge", example = "{\"X-Custom\": \"value\"}")
    private String customHeaders;

    @Schema(description = "Endpoint ID to use for HMAC signature computation")
    private UUID endpointId;

    @Schema(description = "Event type for X-Event-Type header", example = "order.created")
    private String eventType;
}
