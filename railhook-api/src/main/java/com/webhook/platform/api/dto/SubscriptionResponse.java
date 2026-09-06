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
public class SubscriptionResponse {
    private UUID id;
    private UUID projectId;
    private UUID endpointId;
    private String eventType;
    private Boolean enabled;
    private Boolean orderingEnabled;
    private Integer maxAttempts;
    private Integer timeoutSeconds;
    private String retryDelays;
    private String payloadTemplate;
    private String customHeaders;
    private UUID transformationId;
    private String transformationName;
    private Instant createdAt;
    private Instant updatedAt;
}
