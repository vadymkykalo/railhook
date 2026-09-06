package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface IncomingForwardAttemptRepository extends JpaRepository<IncomingForwardAttempt, UUID> {

    Page<IncomingForwardAttempt> findByIncomingEventId(UUID incomingEventId, Pageable pageable);

    List<IncomingForwardAttempt> findByIncomingEventIdAndDestinationIdOrderByAttemptNumberDesc(
            UUID incomingEventId, UUID destinationId);

    @Query("SELECT a FROM IncomingForwardAttempt a WHERE a.status = :status AND a.nextRetryAt <= :now " +
            "ORDER BY a.nextRetryAt ASC")
    List<IncomingForwardAttempt> findPendingRetries(
            @Param("status") ForwardAttemptStatus status,
            @Param("now") Instant now,
            PageRequest pageRequest);

    // ── The Incoming DLQ: Forwards whose Retry Ladder was exhausted ──────────────────

    @Query(value = "SELECT a FROM IncomingForwardAttempt a "
            + "JOIN IncomingEvent e ON a.incomingEventId = e.id "
            + "JOIN IncomingSource s ON e.incomingSourceId = s.id "
            + "WHERE s.projectId = :projectId AND a.status = 'DLQ' ORDER BY a.finishedAt DESC",
            countQuery = "SELECT COUNT(a) FROM IncomingForwardAttempt a "
                    + "JOIN IncomingEvent e ON a.incomingEventId = e.id "
                    + "JOIN IncomingSource s ON e.incomingSourceId = s.id "
                    + "WHERE s.projectId = :projectId AND a.status = 'DLQ'")
    Page<IncomingForwardAttempt> findDlqByProjectId(@Param("projectId") UUID projectId, Pageable pageable);

    @Query(value = "SELECT a FROM IncomingForwardAttempt a "
            + "JOIN IncomingEvent e ON a.incomingEventId = e.id "
            + "JOIN IncomingSource s ON e.incomingSourceId = s.id "
            + "WHERE s.projectId = :projectId AND a.destinationId = :destinationId AND a.status = 'DLQ' "
            + "ORDER BY a.finishedAt DESC",
            countQuery = "SELECT COUNT(a) FROM IncomingForwardAttempt a "
                    + "JOIN IncomingEvent e ON a.incomingEventId = e.id "
                    + "JOIN IncomingSource s ON e.incomingSourceId = s.id "
                    + "WHERE s.projectId = :projectId AND a.destinationId = :destinationId AND a.status = 'DLQ'")
    Page<IncomingForwardAttempt> findDlqByProjectIdAndDestinationId(@Param("projectId") UUID projectId,
            @Param("destinationId") UUID destinationId, Pageable pageable);

    @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a "
            + "JOIN IncomingEvent e ON a.incomingEventId = e.id "
            + "JOIN IncomingSource s ON e.incomingSourceId = s.id "
            + "WHERE s.projectId = :projectId AND a.status = 'DLQ'")
    long countDlqByProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a "
            + "JOIN IncomingEvent e ON a.incomingEventId = e.id "
            + "JOIN IncomingSource s ON e.incomingSourceId = s.id "
            + "WHERE s.projectId = :projectId AND a.status = 'DLQ' AND a.finishedAt >= :since")
    long countDlqByProjectIdSince(@Param("projectId") UUID projectId, @Param("since") Instant since);

    List<IncomingForwardAttempt> findByIdInAndStatus(List<UUID> ids, ForwardAttemptStatus status);

    /**
     * One batch of a purge, deliberately not the whole thing: a project with a large Incoming DLQ
     * used to mean a single unbounded DELETE holding row locks across every matching Attempt.
     *
     * <p>{@code organization_id} is in the predicate because {@code @TenantId} does not reach
     * native SQL: without it this deletes every organization's abandoned Forwards. The caller
     * validates project ownership first; this is the second lock on the door.
     *
     * @return how many rows this call removed, so the caller stops when a batch comes back short
     */
    @Modifying
    @Query(value = """
            DELETE FROM incoming_forward_attempts
             WHERE id IN (
                   SELECT a.id FROM incoming_forward_attempts a
                     JOIN incoming_events e ON e.id = a.incoming_event_id
                     JOIN incoming_sources s ON s.id = e.incoming_source_id
                    WHERE a.organization_id = :organizationId
                      AND a.status = 'DLQ' AND s.project_id = :projectId
                    LIMIT :batchSize
             )
            """, nativeQuery = true)
    int deleteDlqBatchByProjectId(@Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("batchSize") int batchSize);

    // ── Usage aggregation ────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a " +
            "JOIN IncomingEvent e ON a.incomingEventId = e.id " +
            "JOIN IncomingSource s ON e.incomingSourceId = s.id " +
            "WHERE s.projectId = :projectId AND a.status = 'SUCCESS' AND a.finishedAt BETWEEN :from AND :to")
    long countSuccessfulByProjectAndDateRange(@Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COUNT(a) FROM IncomingForwardAttempt a " +
            "JOIN IncomingEvent e ON a.incomingEventId = e.id " +
            "JOIN IncomingSource s ON e.incomingSourceId = s.id " +
            "WHERE s.projectId = :projectId AND a.status = 'SUCCESS' AND a.finishedAt >= :since")
    long countSuccessfulByProjectSince(@Param("projectId") UUID projectId, @Param("since") Instant since);
}
