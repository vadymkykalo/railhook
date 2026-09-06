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
public class DlqItemResponse {
    private UUID deliveryId;
    private UUID eventId;
    private UUID endpointId;
    private UUID subscriptionId;
    private String eventType;
    private String endpointUrl;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String lastError;
    private Instant failedAt;
    private Instant createdAt;
}
