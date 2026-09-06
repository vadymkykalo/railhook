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

    /**
     * Set on a key that has been rotated away. With {@code expiresAt} it is the grace window:
     * this key still authenticates until the expiry, and the expiry is a retirement rather than
     * one the customer asked for.
     */
    private Instant rotatedAt;

    /** The key that took over from this one, for a UI that wants to say what replaced what. */
    private UUID replacedById;
}
