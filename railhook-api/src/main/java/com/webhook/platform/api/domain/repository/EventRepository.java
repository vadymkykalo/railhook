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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    Optional<Event> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);
    List<Event> findByProjectId(UUID projectId);
    List<Event> findByProjectIdAndEventTypeContainingIgnoreCase(UUID projectId, String eventType);
    Page<Event> findByProjectId(UUID projectId, Pageable pageable);

    long countByCreatedAtGreaterThanEqualAndOrganizationIdNot(Instant since, UUID excludedOrganizationId);

    /** Returns {@code [id, eventType]} rows so a delivery list does not load every event payload. */
    @Query("SELECT e.id, e.eventType FROM Event e WHERE e.id IN :ids")
    List<Object[]> findEventTypesByIds(@Param("ids") Collection<UUID> ids);

    @Query("SELECT DISTINCT e.eventType FROM Event e WHERE e.projectId = :projectId AND e.createdAt >= :since")
    List<String> findRecentEventTypes(@Param("projectId") UUID projectId, @Param("since") Instant since,
                                      Pageable pageable);

    @Query("SELECT e.organizationId, COUNT(e) FROM Event e "
            + "WHERE e.createdAt >= :from AND e.createdAt < :to GROUP BY e.organizationId")
    List<Object[]> countPerOrganizationBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT CAST(e.createdAt AS LocalDate), COUNT(e) FROM Event e WHERE e.createdAt >= :since "
            + "AND e.organizationId <> :excluded GROUP BY CAST(e.createdAt AS LocalDate)")
    List<Object[]> countPerDaySinceExcluding(@Param("since") Instant since, @Param("excluded") UUID excludedOrganizationId);

    @Query("SELECT e.organizationId, COUNT(e) FROM Event e WHERE e.organizationId IN :organizationIds "
            + "AND e.createdAt >= :from AND e.createdAt < :to GROUP BY e.organizationId")
    List<Object[]> countForOrganizationsBetween(
            @Param("organizationIds") Collection<UUID> organizationIds,
            @Param("from") Instant from, @Param("to") Instant to);
    Page<Event> findByProjectIdAndEventTypeContainingIgnoreCase(UUID projectId, String eventType, Pageable pageable);
    boolean existsByProjectId(UUID projectId);

    @Query("SELECT COUNT(e) FROM Event e WHERE e.projectId = :projectId AND e.createdAt BETWEEN :from AND :to")
    long countByProjectIdAndCreatedAtBetween(@Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Both directions charge the same monthly quota, so this count, which re-seeds the Redis
     * counter and stands in for it when Redis is down, has to include incoming events too.
     */
    @Query(value = """
        SELECT (SELECT COUNT(*) FROM events e
                 WHERE e.organization_id = :orgId AND e.created_at >= :from AND e.created_at < :to)
             + (SELECT COUNT(*) FROM incoming_events ie
                 WHERE ie.organization_id = :orgId AND ie.received_at >= :from AND ie.received_at < :to)
        """, nativeQuery = true)
    long countEventsAndIncomingEventsBetween(@Param("orgId") UUID organizationId, @Param("from") Instant from, @Param("to") Instant to);

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
     * Deliveries and their attempts go with the event through ON DELETE CASCADE. An event with a
     * PENDING or PROCESSING delivery is skipped however old it is, since a claim may be live on it.
     *
     * <p>No organization_id predicate: this runs as {@code @SystemTenant} across every
     * organization.
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

    @Query(value = "SELECT COALESCE(n_live_tup, 0) FROM pg_stat_user_tables WHERE relname = 'events'",
            nativeQuery = true)
    long estimatedRowCount();

    @Query(value = "SELECT COALESCE(n_live_tup, 0) FROM pg_stat_user_tables WHERE relname = 'deliveries'",
            nativeQuery = true)
    long estimatedDeliveryRowCount();
}
