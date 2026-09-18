package com.webhook.platform.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A webhook tester URL made on the public site without an account.
 *
 * <p>Deliberately not tenant data — there is no organization behind it — so it carries no
 * {@code @TenantId}; the slug is its only identity.
 */
@Entity
@Table(name = "public_bins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicBin {

    @Id
    private UUID id;

    @Column(name = "slug", nullable = false, unique = true, length = 32)
    private String slug;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Who made it, for the per-address cap on live URLs. Never shown. */
    @Column(name = "creator_ip", length = 45)
    private String creatorIp;

    @Column(name = "request_count", nullable = false)
    @Builder.Default
    private long requestCount = 0;
}
