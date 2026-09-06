package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.CompatibilityMode;
import com.webhook.platform.api.domain.enums.SchemaStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_schema_version")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventSchemaVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    @Column(nullable = false)
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", nullable = false, columnDefinition = "jsonb")
    private String schemaJson;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SchemaStatus status = SchemaStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "compatibility_mode", nullable = false, length = 20)
    @Builder.Default
    private CompatibilityMode compatibilityMode = CompatibilityMode.NONE;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_type_id", insertable = false, updatable = false)
    private EventTypeCatalog eventType;
}
