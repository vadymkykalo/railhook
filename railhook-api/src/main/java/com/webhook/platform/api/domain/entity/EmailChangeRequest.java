package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.EmailChangeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One asked-for change of the address an account signs in with; see the V072 migration.
 *
 * <p>Not tenant-scoped: an address belongs to a person, who may be in several organizations.
 * Every read is by user id or by the hash of a token only its holder has.
 */
@Entity
@Table(name = "email_change_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "previous_email", nullable = false)
    private String previousEmail;

    @Column(name = "new_email", nullable = false)
    private String newEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EmailChangeStatus status;

    /** SHA-256 of the token mailed to the new address. Null once applied without one. */
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    /** SHA-256 of the "this wasn't me" token mailed to the old address. */
    @Column(name = "cancel_token_hash", length = 64)
    private String cancelTokenHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
