package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shared_debug_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SharedDebugLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", insertable = false, updatable = false)
    private Event event;
}
