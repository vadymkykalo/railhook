package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionRequest {
    @NotNull(message = "Endpoint ID is required")
    private UUID endpointId;

    @NotBlank(message = "Event type is required")
    @Pattern(regexp = "^(\\*{1,2}|[a-z][a-z0-9_]*)(\\.([a-z][a-z0-9_]*|\\*{1,2}))*$", message = "Event type must be lowercase with dots/underscores, supports wildcards * and ** (e.g. order.created, order.*, **)")
    private String eventType;

    private Boolean enabled;
    
    private Boolean orderingEnabled;

    @Min(value = 1, message = "maxAttempts must be at least 1")
    @Max(value = 20, message = "maxAttempts must be at most 20")
    private Integer maxAttempts;

    @Min(value = 1, message = "timeoutSeconds must be at least 1")
    @Max(value = 60, message = "timeoutSeconds must be at most 60")
    private Integer timeoutSeconds;

    private String retryDelays;

    private String payloadTemplate;

    private String customHeaders;

    private UUID transformationId;
}
