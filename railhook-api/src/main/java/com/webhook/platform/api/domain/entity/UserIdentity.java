package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * An identity provider account linked to a {@link User}. Not tenant-scoped, like the user it
 * belongs to: a person signs in before any organization is chosen.
 */
@Entity
@Table(name = "user_identities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String provider;

    /** The provider's permanent id for the person. The address can change; this cannot. */
    @Column(nullable = false)
    private String subject;

    /** The address the provider vouched for when the link was made, for a person reading the account. */
    @Column(nullable = false)
    private String email;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
