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
 * One published template of a {@link Transformation}, kept so that the version number the product
 * shows means something: it can be read, compared and put back.
 *
 * <p>Append-only. A restore publishes a new row naming the one it came from
 * ({@code restoredFromVersion}); nothing rewrites an existing row, so the history reads as what
 * happened rather than as what the template ended up being.
 *
 * <p>{@code createdBy} is a user id and is null on purpose in three cases: the change came from an
 * API key (no person behind it), the row was backfilled by V083 from a transformation that
 * predates the history, or the user has since been erased — the FK is {@code ON DELETE SET NULL},
 * so an erasure takes the name and leaves the change.
 *
 * <p>There is no worker copy of this entity: the worker resolves a Transformation to its current
 * template and never reads the history.
 */
@Entity
@Table(name = "transformation_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransformationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "transformation_id", nullable = false)
    private UUID transformationId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String template;

    @Column(name = "restored_from_version")
    private Integer restoredFromVersion;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
