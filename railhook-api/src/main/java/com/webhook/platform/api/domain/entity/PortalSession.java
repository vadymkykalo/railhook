package com.webhook.platform.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A short-lived bearer credential for one Consumer, opened by the customer's backend.
 *
 * <p>Only the SHA-256 of the token is kept, as with an API key: the plaintext exists in the
 * response that created it and in the browser it was handed to.
 */
@Entity
@Table(name = "portal_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** The one https origin the portal may be embedded in, or null for any. */
    @Column(name = "allowed_origin")
    private String allowedOrigin;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
