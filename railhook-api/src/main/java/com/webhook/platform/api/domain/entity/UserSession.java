package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.SessionClient;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One refresh-token family. Deliberately not {@code @TenantId}-scoped: a session belongs to a
 * person, who must still see and end sessions left in another organization. Every read is by
 * {@code userId} instead, and {@code UserSessionService} refuses anyone else's session.
 */
@Entity
@Table(name = "user_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    /** Assigned by the caller: the refresh token is minted with it as its {@code sid} claim. */
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** Rotated on every refresh; a token whose jti no longer matches has been superseded. */
    @Column(name = "refresh_token_jti", nullable = false, unique = true, length = 64)
    private String refreshTokenJti;

    @Enumerated(EnumType.STRING)
    @Column(name = "client", nullable = false, length = 16)
    @Builder.Default
    private SessionClient client = SessionClient.WEB;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    @Builder.Default
    private Instant lastSeenAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
