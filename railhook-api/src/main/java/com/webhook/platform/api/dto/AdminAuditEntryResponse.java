package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of an organization's audit log, as the platform admin sees it.
 *
 * <p>Not {@code AuditLogResponse}: that carries {@code details}, the request body the tenant
 * submitted, which the tenant may read in their own log and the platform admin has no need to.
 * Who did what, when, from where, and whether it worked answers every support question the
 * detail view exists for.
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
