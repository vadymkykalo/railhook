package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "schema_change")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchemaChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    @Column(name = "from_version_id")
    private UUID fromVersionId;

    @Column(name = "to_version_id", nullable = false)
    private UUID toVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_summary", nullable = false, columnDefinition = "jsonb")
    private String changeSummary;

    @Column(nullable = false)
    @Builder.Default
    private Boolean breaking = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_type_id", insertable = false, updatable = false)
    private EventTypeCatalog eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_version_id", insertable = false, updatable = false)
    private EventSchemaVersion fromVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_version_id", insertable = false, updatable = false)
    private EventSchemaVersion toVersion;
}
