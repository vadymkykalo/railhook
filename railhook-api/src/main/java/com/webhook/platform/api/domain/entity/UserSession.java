package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.SessionClient;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One live refresh-token family, and the organization it is currently looking at.
 *
 * <p>Refresh tokens are self-contained JWTs, so before this table nothing knew how many were
 * outstanding: logout revoked the one token it was handed and a user had no way to see, let alone
 * end, a session on a machine they no longer have. The Redis blacklist is still what makes a
 * revocation take effect within a request; this is what makes the list of revocable things
 * survive a Redis flush.
 *
 * <p>Deliberately <b>not</b> {@code @TenantId}-scoped on {@link #organizationId}, which is the
 * only entity in this package that owns an organization without being confined to one. A session
 * belongs to a person. Confining the table would mean a user who switched organizations could no
 * longer see — or sign out of — the sessions they left behind in the other one, which is exactly
 * the situation the feature exists for. The safety this gives up is bought back explicitly:
 * every read is by {@code userId}, and every mutation goes through
 * {@code UserSessionService}, which refuses a session belonging to anyone but the caller.
 */
@Entity
@Table(name = "user_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    /**
     * Assigned by the caller rather than generated, because it has to exist before the row does:
     * it is the {@code sid} claim of the refresh token whose jti this row stores, so the token
     * has to be minted with it in hand.
     */
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The organization this session is scoped to; changed by the organization switcher. */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /**
     * The jti of the refresh token this session currently accepts. Rotated on every refresh, so
     * a refresh token whose jti no longer matches has been superseded by a newer one.
     */
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

    /** Live means not revoked and not past its refresh token's own expiry. */
    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
