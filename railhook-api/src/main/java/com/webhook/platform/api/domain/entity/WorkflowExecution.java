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

    /** When a suspended execution becomes due. Null unless {@code status == WAITING}. */
    @Column(name = "resume_at")
    private Instant resumeAt;

    /**
     * Engine-private snapshot taken at a suspension: the outputs produced so far, the nodes
     * already skipped, and which node to continue from. Opaque to SQL — written and read only
     * by {@code WorkflowEngine}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resume_state", columnDefinition = "jsonb")
    private String resumeState;

    /**
     * Milliseconds spent actually executing nodes, accumulated across suspensions.
     *
     * <p>The global execution timeout is a budget for work. Measuring it as wall-clock from
     * {@code startedAt} would make a workflow containing a delay longer than the budget
     * impossible to finish: it would time out on the resume every time, having done almost
     * nothing.
     */
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
        /** Suspended at a delay node, due at {@code resumeAt}. Not running, not finished. */
        WAITING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
