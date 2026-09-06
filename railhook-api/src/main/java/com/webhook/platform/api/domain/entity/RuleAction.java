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
@Table(name = "rule_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ActionType type;

    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Column(name = "transformation_id")
    private UUID transformationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private String config = "{}";

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", insertable = false, updatable = false)
    private Rule rule;

    public enum ActionType {
        ROUTE,
        TRANSFORM,
        DROP,
        TAG
    }
}
