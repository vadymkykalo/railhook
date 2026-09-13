package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A completed provider sign-in waiting for the dashboard to collect it. Holds the hash of a code
 * that is valid once, for a minute; see the V071 migration for why a code and not a token.
 */
@Entity
@Table(name = "sign_in_handoffs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignInHandoff {

    @Id
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Whether this sign-in created the account, so the dashboard can welcome a new person. */
    @Column(name = "account_created", nullable = false)
    private boolean accountCreated;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
