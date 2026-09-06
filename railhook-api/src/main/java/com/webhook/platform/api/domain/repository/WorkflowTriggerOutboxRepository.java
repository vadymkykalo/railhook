package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.WorkflowTriggerOutbox;
import com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkflowTriggerOutboxRepository extends JpaRepository<WorkflowTriggerOutbox, UUID> {

    @Query(value = """
        WITH fair_batch AS (
            SELECT id FROM (
                SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY created_at ASC) AS rn
                FROM workflow_trigger_outbox
                WHERE status = 'PENDING'
            ) sub WHERE rn <= :maxPerProject
            ORDER BY rn ASC
            LIMIT :batchSize
        ),
        locked AS (
            SELECT id FROM workflow_trigger_outbox
            WHERE id IN (SELECT id FROM fair_batch)
            FOR UPDATE SKIP LOCKED
        )
        UPDATE workflow_trigger_outbox
        SET status = 'PROCESSING', attempts = attempts + 1
        WHERE id IN (SELECT id FROM locked)
        RETURNING *
        """, nativeQuery = true)
    List<WorkflowTriggerOutbox> claimBatch(@Param("batchSize") int batchSize, @Param("maxPerProject") int maxPerProject);

    /**
     * Returns rows abandoned mid-flight to the queue.
     *
     * <p>{@link #claimBatch} flips a row to PROCESSING and hands it to an executor. If that
     * hand-off never completes — the pod dies, or the task is dropped without the caller
     * being told, which is what happened before the workflow executor learnt to throw — the
     * row keeps that status forever: claimBatch selects PENDING only, and the cleanup delete
     * touches processed rows only. Nothing else in the system reclaims it, so the workflow
     * silently never runs and the table grows without bound.</p>
     *
     * <p>Attempts is deliberately not incremented: being abandoned is not an attempt, and
     * charging for it would retire a row that has never actually run.</p>
     */
    @Modifying
    @Query("""
        UPDATE WorkflowTriggerOutbox w
           SET w.status = com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus.PENDING
         WHERE w.status = com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus.PROCESSING
           AND w.createdAt < :before
        """)
    int reclaimStalledRows(@Param("before") Instant before);

    @Modifying
    @Query("DELETE FROM WorkflowTriggerOutbox w WHERE w.status = :status AND w.processedAt < :before")
    int deleteByStatusAndProcessedAtBefore(
            @Param("status") WorkflowTriggerOutboxStatus status,
            @Param("before") Instant before);
}
