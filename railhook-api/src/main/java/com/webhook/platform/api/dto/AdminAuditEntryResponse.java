package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Not {@code AuditLogResponse}: the platform admin has no need for {@code details}, the request
 * body the tenant submitted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditEntryResponse {

    private UUID id;
    private String action;
    private String resourceType;
    private UUID resourceId;
    private String actorEmail;
    private String status;
    private String clientIp;
    private Instant createdAt;
}
