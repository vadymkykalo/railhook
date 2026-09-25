package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A completed provider sign-in waiting for the dashboard to collect it, by a hashed code valid
 * once, for a minute.
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
