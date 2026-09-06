package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {
    private UUID id;
    private String action;
    private String resourceType;
    private UUID resourceId;
    private UUID userId;
    private String userEmail;
    private UUID organizationId;
    private String status;
    private String errorMessage;
    private Integer durationMs;
    private String clientIp;
    private String details;
    private Instant createdAt;
}
