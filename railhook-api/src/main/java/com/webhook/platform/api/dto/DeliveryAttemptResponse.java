package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryAttemptResponse {
    private UUID id;
    private UUID deliveryId;
    private Integer attemptNumber;
    private String requestHeaders;
    private String requestBody;
    private Integer httpStatusCode;
    private String responseHeaders;
    private String responseBody;
    private String errorMessage;
    private Integer durationMs;
    private Instant createdAt;
}
