package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_trigger_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTriggerOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_payload", columnDefinition = "TEXT")
    private String eventPayload;

    @Column(nullable = false)
    @Builder.Default
    private int depth = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WorkflowTriggerOutboxStatus status = WorkflowTriggerOutboxStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(columnDefinition = "TEXT")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When {@code claimBatch} handed this row to an executor; null until then.
     *
     * <p>{@code reclaimStalledRows} measures staleness from here rather than from
     * {@code createdAt}: a row waiting on its project's concurrency ceiling can be hours old
     * before anyone claims it, and that is not a stalled run.
     */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
