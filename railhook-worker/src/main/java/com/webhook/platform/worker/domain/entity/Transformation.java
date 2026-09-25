package com.webhook.platform.worker.domain.entity;

import com.webhook.platform.common.transform.TransformationKind;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transformations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transformation {

    @Id
    private UUID id;

    /** Not tenant-filtered here: the worker has no request tenant. Mapped so attempt rows can copy it. */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String template;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransformationKind kind;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
