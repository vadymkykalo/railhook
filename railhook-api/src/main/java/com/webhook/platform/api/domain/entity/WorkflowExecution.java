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
@Table(name = "workflow_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class WorkflowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "trigger_event_id")
    private UUID triggerEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.RUNNING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_data", columnDefinition = "jsonb")
    private String triggerData;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Integer durationMs;

    // Null unless WAITING.
    @Column(name = "resume_at")
    private Instant resumeAt;

    // Opaque to SQL: only WorkflowEngine reads and writes it.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resume_state", columnDefinition = "jsonb")
    private String resumeState;

    // The timeout budgets node execution time, not wall-clock, or a long delay node could
    // never finish.
    @Column(name = "working_ms")
    private Long workingMs;

    @Column(nullable = false)
    @Builder.Default
    private Integer depth = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", insertable = false, updatable = false)
    private Workflow workflow;

    public enum ExecutionStatus {
        RUNNING,
        WAITING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
