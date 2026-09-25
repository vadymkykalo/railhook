package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.SessionClient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries no token material, not even a prefix: any access token can read this list, and it must
 * not become a way to upgrade one into a longer-lived credential.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {

    private UUID id;

    private SessionClient client;

    private String userAgent;

    private String ipAddress;

    private Instant createdAt;

    private Instant lastSeenAt;

    private Instant expiresAt;

    private boolean current;
}
