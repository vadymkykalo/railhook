package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rule_execution_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private Boolean matched;

    @Column(name = "actions_executed", nullable = false)
    @Builder.Default
    private Integer actionsExecuted = 0;

    @Column(name = "evaluation_time_ms")
    private Integer evaluationTimeMs;

    @CreationTimestamp
    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;
}
