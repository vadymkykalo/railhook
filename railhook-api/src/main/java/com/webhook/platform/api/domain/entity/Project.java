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
 * A project, and only a live one.
 *
 * <p>Deleting a project stamps {@code deleted_at} and keeps the row. For as long as only the
 * project list read that column, a deleted project went on working everywhere else: its API keys
 * authenticated, its sources took webhooks, its endpoints could still be edited — some fifty
 * lookups by id, each one a place to forget the check. The restriction puts the check where no
 * lookup can forget it, {@code findById} and {@code existsById} included, so a deleted project
 * answers exactly as a project that never existed does.
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
