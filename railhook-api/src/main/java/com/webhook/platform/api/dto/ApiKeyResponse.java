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
public class ApiKeyResponse {
    private UUID id;
    private UUID projectId;
    private String name;
    private String keyPrefix;
    private Instant lastUsedAt;
    private Instant createdAt;
    private Instant revokedAt;
    private Instant expiresAt;
    private String scope;
    private String key;

    /** Set on a rotated key, which still authenticates until {@code expiresAt}. */
    private Instant rotatedAt;

    private UUID replacedById;
}
