package com.webhook.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GdprExportDto(
        String exportVersion,
        Instant exportedAt,
        OrganizationData organization,
        List<MemberData> members,
        List<ProjectData> projects,
        List<AuditLogData> auditLogs
) {

    @Builder
    public record OrganizationData(
            UUID id,
            String name,
            String billingEmail,
            String plan,
            String billingStatus,
            Instant createdAt
    ) {}

    @Builder
    public record MemberData(
            UUID userId,
            String email,
            String fullName,
            String role,
            String status,
            Instant joinedAt
    ) {}

    @Builder
    public record ProjectData(
            UUID id,
            String name,
            String description,
            Instant createdAt,
            List<EndpointData> endpoints,
            List<SubscriptionData> subscriptions,
            List<IncomingSourceData> incomingSources,
            List<ApiKeyData> apiKeys
    ) {}

    @Builder
    public record EndpointData(
            UUID id,
            String url,
            String description,
            Boolean enabled,
            Boolean mtlsEnabled,
            Integer rateLimitPerSecond,
            Instant createdAt
    ) {}

    @Builder
    public record SubscriptionData(
            UUID id,
            UUID endpointId,
            String eventType,
            Boolean enabled,
            Boolean orderingEnabled,
            Integer maxAttempts,
            Integer timeoutSeconds,
            Instant createdAt
    ) {}

    @Builder
    public record IncomingSourceData(
            UUID id,
            String name,
            String slug,
            String providerType,
            String verificationMode,
            String status,
            Instant createdAt,
            List<IncomingDestinationData> destinations
    ) {}

    @Builder
    public record IncomingDestinationData(
            UUID id,
            String url,
            String authType,
            Boolean enabled,
            Integer maxAttempts,
            Integer timeoutSeconds,
            Instant createdAt
    ) {}

    @Builder
    public record ApiKeyData(
            UUID id,
            String name,
            String keyPrefix,
            String scope,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt
    ) {}

    @Builder
    public record AuditLogData(
            String action,
            String resourceType,
            UUID resourceId,
            String status,
            String clientIp,
            Instant createdAt
    ) {}
}
