package com.webhook.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookCaptureResponse {
    private boolean success;
    private String message;
    private String requestId;
    private String receivedAt;
    private String error;
    private String challenge;
}
