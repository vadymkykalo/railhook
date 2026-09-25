package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.IdempotencyPolicy;
import com.webhook.platform.api.domain.enums.SchemaValidationPolicy;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Deletion is soft. The restriction applies to every lookup, {@code findById} included, so a
 * deleted project behaves exactly like one that never existed.
 */
@Entity
@Table(name = "projects")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "schema_validation_enabled", nullable = false)
    @Builder.Default
    private Boolean schemaValidationEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "schema_validation_policy", nullable = false, length = 10)
    @Builder.Default
    private SchemaValidationPolicy schemaValidationPolicy = SchemaValidationPolicy.WARN;

    @Enumerated(EnumType.STRING)
    @Column(name = "idempotency_policy", nullable = false, length = 10)
    @Builder.Default
    private IdempotencyPolicy idempotencyPolicy = IdempotencyPolicy.NONE;
}
