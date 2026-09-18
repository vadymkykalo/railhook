package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/**
 * An MCP app a person connected to one project: the OAuth counterpart of an {@link ApiKey}.
 *
 * <p>It carries exactly what a key carries — organization, project, {@link ApiKeyScope} — so on
 * {@code /mcp} the two are the same kind of caller. What a key does not have is a person behind
 * it: {@link #userId} approved the grant, and it keeps working only while they could still approve
 * it today.
 *
 * <p>Holds the one live access token and the one live refresh token, as hashes. Refreshing
 * replaces both; {@link #previousRefreshTokenHash} is kept so that a replayed refresh token is
 * recognised as one.
 */
@Entity
@Table(name = "oauth_grants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** {@link OAuthClient#getId()}, not the public client_id string. */
    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private ApiKeyScope scope;

    @Column(name = "redirect_uri", nullable = false, length = 2000)
    private String redirectUri;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(name = "code_hash", unique = true, length = 64)
    private String codeHash;

    @Column(name = "code_expires_at")
    private Instant codeExpiresAt;

    @Column(name = "code_used_at")
    private Instant codeUsedAt;

    @Column(name = "access_token_hash", unique = true, length = 64)
    private String accessTokenHash;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "refresh_token_hash", unique = true, length = 64)
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private Instant refreshTokenExpiresAt;

    @Column(name = "previous_refresh_token_hash", length = 64)
    private String previousRefreshTokenHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
