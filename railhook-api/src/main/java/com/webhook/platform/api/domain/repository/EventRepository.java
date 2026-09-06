package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    Optional<Event> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);
    List<Event> findByProjectId(UUID projectId);
    List<Event> findByProjectIdAndEventTypeContainingIgnoreCase(UUID projectId, String eventType);
    Page<Event> findByProjectId(UUID projectId, Pageable pageable);
    Page<Event> findByProjectIdAndEventTypeContainingIgnoreCase(UUID projectId, String eventType, Pageable pageable);
    boolean existsByProjectId(UUID projectId);

    @Query("SELECT COUNT(e) FROM Event e WHERE e.projectId = :projectId AND e.createdAt BETWEEN :from AND :to")
    long countByProjectIdAndCreatedAtBetween(@Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
        SELECT COUNT(*) FROM events e
        WHERE e.organization_id = :orgId AND e.created_at >= :from AND e.created_at < :to
        """, nativeQuery = true)
    long countByOrganizationIdAndCreatedAtBetween(@Param("orgId") UUID organizationId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
        SELECT 
            CAST(e.id AS text),
            e.event_type,
            e.created_at,
            COUNT(d.id) as delivery_count
        FROM events e
        LEFT JOIN deliveries d ON d.event_id = e.id
        WHERE e.organization_id = :organizationId AND e.project_id = :projectId
        GROUP BY e.id, e.event_type, e.created_at
        ORDER BY e.created_at DESC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> findRecentEventsWithDeliveryCount(
 @Param("organizationId") UUID organizationId,@Param("projectId") UUID projectId);

    @Query(value = """
        SELECT 
            e.event_type as event_type,
            COUNT(*) as event_count,
            COUNT(*) FILTER (WHERE d.status = 'SUCCESS') as success_count
        FROM events e
        LEFT JOIN deliveries d ON d.event_id = e.id
        WHERE e.organization_id = :organizationId AND e.project_id = :projectId AND e.created_at BETWEEN :from AND :to
        GROUP BY e.event_type
        ORDER BY event_count DESC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> findEventTypeBreakdownByProjectId(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // --- Event Time Machine: cursor-based scanning (no OFFSET, highload-safe) ---

    @Query(value = """
        SELECT e.* FROM events e
        WHERE e.organization_id = :organizationId AND e.project_id = :projectId
          AND e.created_at >= :fromDate AND e.created_at <= :toDate
          AND (e.created_at, e.id) > (:cursorCreatedAt, :cursorId)
        ORDER BY e.created_at, e.id
        LIMIT :batchSize
        """, nativeQuery = true)
    List<Event> findByCursorForReplay(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT e.* FROM events e
        WHERE e.organization_id = :organizationId AND e.project_id = :projectId
          AND e.created_at >= :fromDate AND e.created_at <= :toDate
          AND e.event_type = :eventType
          AND (e.created_at, e.id) > (:cursorCreatedAt, :cursorId)
        ORDER BY e.created_at, e.id
        LIMIT :batchSize
        """, nativeQuery = true)
    List<Event> findByCursorForReplayWithEventType(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            @Param("eventType") String eventType,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT COUNT(*) FROM events e
        WHERE e.organization_id = :organizationId AND e.project_id = :projectId
          AND e.created_at >= :fromDate AND e.created_at <= :toDate
        """, nativeQuery = true)
    long countForReplay(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate);

    @Query(value = """
        SELECT COUNT(*) FROM events e
        WHERE e.organization_id = :organizationId AND e.project_id = :projectId
          AND e.created_at >= :fromDate AND e.created_at <= :toDate
          AND e.event_type = :eventType
        """, nativeQuery = true)
    long countForReplayWithEventType(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            @Param("eventType") String eventType);

    /**
     * Deletes one batch of events past the retention cutoff, newest-safe.
     *
     * <p>The only thing in the codebase that bounds the growth of {@code events} and, through
     * it, {@code deliveries}. Retention for those two used to live solely in
     * {@code RetentionCleanupScheduler}, which returns immediately unless billing is enabled —
     * so the self-hosted default, which is also the recommended deployment, deleted neither
     * ever. {@code delivery_attempts} partitions were being dropped at 90 days while the parent
     * rows carrying the payloads stayed forever.
     *
     * <p>One statement, because {@code deliveries.event_id} is {@code ON DELETE CASCADE} to here
     * (V001) and {@code delivery_attempts.delivery_id} is {@code ON DELETE CASCADE} to
     * deliveries (V061): deleting the event takes the whole tree with it. Doing it in three
     * hand-ordered steps, as the billing scheduler does, only reproduces what the constraints
     * already guarantee.
     *
     * <p>An event with a delivery still PENDING or PROCESSING is left alone however old it is.
     * Those rows are owned by the pipeline — a claim may be live on one — and deleting an event
     * out from under an in-flight attempt is a far worse failure than keeping it another day.
     *
     * <p>Native, for the {@code LIMIT} that JPQL has no bulk-delete form of; the subquery is what
     * makes the limit apply to the rows chosen rather than to the delete. Deliberately carries no
     * {@code organization_id}: it runs {@code @SystemTenant} across every organization, and a
     * tenant predicate would leave every other organization's rows behind — which is the bug.
     * Listed in {@code NativeQueryTenantPredicateTest.SYSTEM_PATHS} with that reason.
     *
     * @return how many rows this call removed, so the caller stops when a batch comes back short
     */
    @Modifying
    @Query(value = """
        DELETE FROM events
         WHERE id IN (
               SELECT e.id FROM events e
                WHERE e.created_at < :cutoff
                  AND NOT EXISTS (
                      SELECT 1 FROM deliveries d
                       WHERE d.event_id = e.id
                         AND d.status IN ('PENDING', 'PROCESSING')
                  )
                LIMIT :limit
         )
        """, nativeQuery = true)
    int deleteOldEvents(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Estimated row count for {@code events}, for the gauge that makes growth visible. */
    @Query(value = "SELECT COALESCE(n_live_tup, 0) FROM pg_stat_user_tables WHERE relname = 'events'",
            nativeQuery = true)
    long estimatedRowCount();

    /** Estimated row count for {@code deliveries} — one row per fan-out, so the larger of the two. */
    @Query(value = "SELECT COALESCE(n_live_tup, 0) FROM pg_stat_user_tables WHERE relname = 'deliveries'",
            nativeQuery = true)
    long estimatedDeliveryRowCount();
}
