package com.webhook.platform.api.domain.entity;

import com.webhook.platform.common.transform.TransformationKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Append-only: a restore publishes a new row naming the one it came from.
 *
 * <p>{@code createdBy} is null for API-key changes, backfilled rows and erased users. No worker
 * copy exists because the worker never reads the history.
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

    // Per version, so restoring an old template also restores its language.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private TransformationKind kind = TransformationKind.TEMPLATE;

    @Column(name = "restored_from_version")
    private Integer restoredFromVersion;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
