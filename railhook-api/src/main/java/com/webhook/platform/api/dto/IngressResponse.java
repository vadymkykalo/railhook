package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response from incoming webhook ingress endpoint")
public class IngressResponse {

    @Schema(description = "Processing status", example = "accepted")
    private String status;

    @Schema(description = "Unique request ID assigned to the received webhook", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String requestId;

    @Schema(description = "Error code if the request was rejected", example = "not_found")
    private String error;

    @Schema(description = "Human-readable message", example = "Webhook accepted for processing")
    private String message;
}
