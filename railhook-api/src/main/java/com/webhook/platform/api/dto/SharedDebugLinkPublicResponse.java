package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SharedDebugLinkPublicResponse {
    private String eventType;
    private String sanitizedPayload;
    private Instant eventCreatedAt;
    private Instant linkExpiresAt;
    private String projectName;
}
