package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Delivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.webhook.platform.api.domain.enums.DeliveryStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, UUID>, JpaSpecificationExecutor<Delivery> {
    Page<Delivery> findByEventId(UUID eventId, Pageable pageable);
    Page<Delivery> findByEventIdIn(List<UUID> eventIds, Pageable pageable);

    /** Deliveries in those states since then, one organization's excepted: the overview leaves the public demo out. */
    long countByStatusInAndCreatedAtGreaterThanEqualAndOrganizationIdNot(
            Collection<DeliveryStatus> statuses, Instant since, UUID excludedOrganizationId);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.event.projectId = :projectId AND d.createdAt BETWEEN :from AND :to")
    long countByProjectIdAndCreatedAtBetween(@Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM Delivery d WHERE d.event.projectId = :projectId")
    boolean existsByProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.event.projectId = :projectId AND d.status = :status AND d.createdAt BETWEEN :from AND :to")
    long countByProjectIdAndStatusAndCreatedAtBetween(@Param("projectId") UUID projectId, @Param("status") DeliveryStatus status, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
        SELECT 
            TO_CHAR(DATE_TRUNC('hour', d.created_at), 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as ts,
            COUNT(*) as total,
            COUNT(*) FILTER (WHERE d.status = 'SUCCESS') as success,
            COUNT(*) FILTER (WHERE d.status IN ('FAILED', 'DLQ')) as failed
        FROM deliveries d
        JOIN events e ON d.event_id = e.id
        WHERE d.organization_id = :organizationId
          AND e.project_id = :projectId AND d.created_at BETWEEN :from AND :to
        GROUP BY DATE_TRUNC('hour', d.created_at)
        ORDER BY ts
        """, nativeQuery = true)
    List<Object[]> findDeliveryTimeSeriesByHour(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(value = """
        SELECT 
            TO_CHAR(DATE_TRUNC('day', d.created_at), 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as ts,
            COUNT(*) as total,
            COUNT(*) FILTER (WHERE d.status = 'SUCCESS') as success,
            COUNT(*) FILTER (WHERE d.status IN ('FAILED', 'DLQ')) as failed
        FROM deliveries d
        JOIN events e ON d.event_id = e.id
        WHERE d.organization_id = :organizationId
          AND e.project_id = :projectId AND d.created_at BETWEEN :from AND :to
        GROUP BY DATE_TRUNC('day', d.created_at)
        ORDER BY ts
        """, nativeQuery = true)
    List<Object[]> findDeliveryTimeSeriesByDay(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(value = """
        SELECT 
            CAST(e.id AS text) as endpoint_id,
            e.url,
            e.enabled,
            COUNT(d.*) as total_deliveries,
            COUNT(*) FILTER (WHERE d.status = 'SUCCESS') as successful,
            COUNT(*) FILTER (WHERE d.status IN ('FAILED', 'DLQ')) as failed,
            AVG(da.duration_ms) as avg_latency,
            PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY da.duration_ms) as p95_latency,
            MAX(d.created_at) as last_delivery
        FROM endpoints e
        LEFT JOIN subscriptions s ON s.endpoint_id = e.id
        LEFT JOIN deliveries d ON d.endpoint_id = e.id AND d.created_at BETWEEN :from AND :to
        LEFT JOIN delivery_attempts da ON da.delivery_id = d.id
        WHERE e.organization_id = :organizationId AND e.project_id = :projectId
        GROUP BY e.id, e.url, e.enabled
        ORDER BY total_deliveries DESC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> findEndpointPerformanceByProjectId(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(value = "SELECT d FROM Delivery d JOIN FETCH d.event JOIN FETCH d.endpoint WHERE d.status = 'DLQ' AND d.event.projectId = :projectId ORDER BY d.failedAt DESC",
            countQuery = "SELECT COUNT(d) FROM Delivery d WHERE d.status = 'DLQ' AND d.event.projectId = :projectId")
    Page<Delivery> findDlqByProjectId(@Param("projectId") UUID projectId, Pageable pageable);

    @Query(value = "SELECT d FROM Delivery d JOIN FETCH d.event JOIN FETCH d.endpoint WHERE d.status = 'DLQ' AND d.event.projectId = :projectId AND d.endpointId = :endpointId ORDER BY d.failedAt DESC",
            countQuery = "SELECT COUNT(d) FROM Delivery d WHERE d.status = 'DLQ' AND d.event.projectId = :projectId AND d.endpointId = :endpointId")
    Page<Delivery> findDlqByProjectIdAndEndpointId(@Param("projectId") UUID projectId, @Param("endpointId") UUID endpointId, Pageable pageable);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'DLQ' AND d.event.projectId = :projectId")
    long countDlqByProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'DLQ' AND d.event.projectId = :projectId AND d.failedAt >= :since")
    long countDlqByProjectIdSince(@Param("projectId") UUID projectId, @Param("since") Instant since);

    /**
     * In-flight rows are excluded: counting them as "not a failure" would reset the
     * consecutive-failures streak whenever the endpoint is busy.
     */
    @Query("SELECT d.status FROM Delivery d WHERE d.endpointId = :endpointId "
            + "AND d.status IN ('SUCCESS', 'FAILED', 'DLQ') ORDER BY d.createdAt DESC")
    List<DeliveryStatus> findRecentOutcomesByEndpointId(
            @Param("endpointId") UUID endpointId, Pageable pageable);

    @Query("SELECT d.eventId, d.status, COUNT(d) FROM Delivery d WHERE d.eventId IN :eventIds GROUP BY d.eventId, d.status")
    List<Object[]> countByEventIdsAndStatus(@Param("eventIds") List<UUID> eventIds);

    List<Delivery> findByIdInAndStatus(List<UUID> ids, DeliveryStatus status);

    @Query(value = """
        SELECT CAST(d.status AS text), COUNT(*)
        FROM deliveries d
        JOIN events e ON d.event_id = e.id
        WHERE d.organization_id = :organizationId
          AND e.project_id = :projectId AND d.created_at >= :since
        GROUP BY d.status
        """, nativeQuery = true)
    List<Object[]> countByProjectIdGroupByStatus(
 @Param("organizationId") UUID organizationId,@Param("projectId") UUID projectId, @Param("since") Instant since);

    @Query(value = """
        SELECT d.endpoint_id, CAST(d.status AS text), COUNT(*)
        FROM deliveries d
        WHERE d.organization_id = :organizationId
          AND d.endpoint_id IN :endpointIds AND d.created_at >= :since
        GROUP BY d.endpoint_id, d.status
        """, nativeQuery = true)
    List<Object[]> countByEndpointIdsGroupByEndpointAndStatus(
 @Param("organizationId") UUID organizationId,@Param("endpointIds") List<UUID> endpointIds, @Param("since") Instant since);

    /**
     * Bounded so a purge does not hold locks across the whole DLQ and its cascade into
     * {@code delivery_attempts}. Native because JPQL has no {@code LIMIT} on a bulk delete, so it
     * carries {@code organization_id} itself: {@code @TenantId} does not reach native SQL.
     */
    @Modifying
    @Query(value = """
            DELETE FROM deliveries
             WHERE id IN (
                   SELECT d.id FROM deliveries d
                     JOIN events e ON e.id = d.event_id
                    WHERE d.organization_id = :organizationId
                      AND d.status = 'DLQ' AND e.project_id = :projectId
                    LIMIT :batchSize
             )
            """, nativeQuery = true)
    int deleteDlqBatchByProjectId(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("batchSize") int batchSize);

    /** Reseeds the Redis sequence counter after it is lost, instead of restarting from zero. */
    @Query("SELECT MAX(d.sequenceNumber) FROM Delivery d WHERE d.endpointId = :endpointId")
    Long findMaxSequenceNumber(@Param("endpointId") UUID endpointId);

    /** Limited to recently active endpoints so reconciliation stays cheap as endpoints grow. */
    @Query(value = """
        SELECT d.endpoint_id, MAX(d.sequence_number)
        FROM deliveries d
        WHERE d.ordering_enabled = true AND d.sequence_number IS NOT NULL AND d.created_at >= :since
        GROUP BY d.endpoint_id
        """, nativeQuery = true)
    List<Object[]> findMaxSequenceNumberPerEndpointSince(@Param("since") Instant since);

    /**
     * Runs outside the ingest transaction, so a rollback there cannot waste a generated number.
     * Transactional itself because both callers have no transaction open and a JPQL update
     * needs one.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Delivery d SET d.sequenceNumber = :sequenceNumber WHERE d.id = :id")
    int updateSequenceNumber(@Param("id") UUID id, @Param("sequenceNumber") long sequenceNumber);

    /**
     * Ordered Deliveries left without a sequence number by a pod that died between commit and
     * backfill. {@code :before} keeps the sweep off rows whose backfill is still in flight.
     */
    @Query(value = """
        SELECT * FROM deliveries d
        WHERE d.ordering_enabled = true
          AND d.sequence_number IS NULL
          AND d.status IN ('PENDING', 'PROCESSING')
          AND d.created_at < :before
        ORDER BY d.created_at ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<Delivery> findOrderedDeliveriesMissingASequence(@Param("before") Instant before,
            @Param("limit") int limit);

}
