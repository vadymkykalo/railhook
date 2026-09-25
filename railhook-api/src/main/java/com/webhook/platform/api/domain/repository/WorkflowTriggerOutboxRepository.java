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
        SET status = 'PROCESSING', attempts = attempts + 1, claimed_at = NOW()
        WHERE id IN (SELECT id FROM locked)
        RETURNING *
        """, nativeQuery = true)
    List<WorkflowTriggerOutbox> claimBatch(@Param("batchSize") int batchSize, @Param("maxPerProject") int maxPerProject);

    /**
     * Returns rows abandoned in PROCESSING (dead pod, dropped task) to the queue; nothing else
     * reclaims them. Attempts is not incremented, since being abandoned is not an attempt.
     *
     * <p>Staleness is measured from {@code claimedAt}, not {@code createdAt}: a busy project's
     * rows wait in PENDING past the threshold, and would be reclaimed while still running.
     */
    @Modifying
    @Query("""
        UPDATE WorkflowTriggerOutbox w
           SET w.status = com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus.PENDING
         WHERE w.status = com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus.PROCESSING
           AND w.claimedAt IS NOT NULL
           AND w.claimedAt < :before
        """)
    int reclaimStalledRows(@Param("before") Instant before);

    @Modifying
    @Query("DELETE FROM WorkflowTriggerOutbox w WHERE w.status = :status AND w.processedAt < :before")
    int deleteByStatusAndProcessedAtBefore(
            @Param("status") WorkflowTriggerOutboxStatus status,
            @Param("before") Instant before);
}
