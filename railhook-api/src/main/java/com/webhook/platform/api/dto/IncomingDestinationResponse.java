package com.webhook.platform.api.dto;

import com.webhook.platform.common.enums.IncomingAuthType;
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
public class IncomingDestinationResponse {
    private UUID id;
    private UUID incomingSourceId;
    private String url;
    private IncomingAuthType authType;
    private boolean authConfigured;
    private String customHeadersJson;
    private boolean enabled;
    private int maxAttempts;
    private int timeoutSeconds;
    private String retryDelays;
    private String payloadTransform;
    private UUID transformationId;
    private String transformationName;
    private Instant createdAt;
    private Instant updatedAt;
}
