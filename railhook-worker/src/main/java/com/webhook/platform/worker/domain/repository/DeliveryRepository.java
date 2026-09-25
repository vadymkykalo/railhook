package com.webhook.platform.worker.domain.repository;

import com.webhook.platform.common.demo.DemoTenant;
import com.webhook.platform.worker.domain.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    // Clears the token so the abandoned attempt's late finalizer cannot write to the row.
    @Modifying
    @Query(value = "UPDATE deliveries SET status = 'PENDING', claim_token = NULL, " +
           "next_retry_at = now(), updated_at = now(), version = version + 1 " +
           "WHERE status = 'PROCESSING' AND (last_attempt_at < :threshold OR (last_attempt_at IS NULL AND updated_at < :threshold))", nativeQuery = true)
    int resetStuckDeliveries(@Param("threshold") Instant threshold);

    /** Gated on updated_at so freshly ingested deliveries are not swept. */
    @Modifying
    @Query(value = "UPDATE deliveries SET next_retry_at = now(), updated_at = now(), version = version + 1 " +
           "WHERE status = 'PENDING' AND next_retry_at IS NULL AND updated_at < :threshold", nativeQuery = true)
    int resetStrandedPendingDeliveries(@Param("threshold") Instant threshold);


    @Query(value = """
            SELECT id FROM (
                SELECT d.id,
                       ROW_NUMBER() OVER (PARTITION BY d.endpoint_id ORDER BY d.next_retry_at ASC) AS rn_ep,
                       ROW_NUMBER() OVER (PARTITION BY e.project_id ORDER BY d.next_retry_at ASC) AS rn_proj
                FROM deliveries d
                JOIN endpoints e ON d.endpoint_id = e.id
                WHERE d.status = :#{#status.name()} AND d.next_retry_at IS NOT NULL AND d.next_retry_at <= :now
            ) sub WHERE rn_ep <= :maxPerEndpoint AND rn_proj <= :maxPerProject
            ORDER BY rn_proj ASC, rn_ep ASC LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findPendingRetryIds(
            @Param("status") Delivery.DeliveryStatus status,
            @Param("now") Instant now,
            @Param("limit") int limit,
            @Param("maxPerEndpoint") int maxPerEndpoint,
            @Param("maxPerProject") int maxPerProject
    );

    @Query(value = """
            SELECT * FROM deliveries WHERE id IN :ids ORDER BY next_retry_at ASC FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Delivery> lockByIds(@Param("ids") List<UUID> ids);

    // Not before its rung: a duplicate dispatch message matching on status alone used to run an
    // Attempt early and spend a rung. :now comes from the application because next_retry_at has
    // no time zone and the database's now() depends on the session's zone.
    @Modifying
    @Query(value = "UPDATE deliveries SET status = 'PROCESSING', claim_token = :claimToken, " +
            "last_attempt_at = now(), updated_at = now(), version = version + 1 " +
            "WHERE id = :id AND status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :now)",
            nativeQuery = true)
    int claimForProcessing(@Param("id") UUID id, @Param("claimToken") UUID claimToken, @Param("now") Instant now);

    @Query(value = "UPDATE deliveries SET status = 'PROCESSING', claim_token = :claimToken, " +
            "last_attempt_at = now(), updated_at = now(), version = version + 1 " +
            "WHERE id = :id AND status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :now) " +
            "RETURNING *", nativeQuery = true)
    Delivery claimForProcessingAndReturn(@Param("id") UUID id, @Param("claimToken") UUID claimToken,
            @Param("now") Instant now);

    /**
     * Swaps the scheduler's token for a fresh one, so a redelivered message or a late send the
     * scheduler gave up on claims nothing.
     *
     * <p>Restamps {@code last_attempt_at}, which the stuck sweep measures from. Left at the
     * scheduler's time, a message that sat in the retry topic past the sweep threshold was reset
     * while its POST was on the wire and sent twice.
     */
    @Query(value = "UPDATE deliveries SET claim_token = :newClaimToken, " +
            "last_attempt_at = now(), updated_at = now(), version = version + 1 " +
            "WHERE id = :id AND status = 'PROCESSING' AND claim_token = :expectedClaimToken " +
            "RETURNING *", nativeQuery = true)
    Delivery claimRetryForProcessing(@Param("id") UUID id,
            @Param("expectedClaimToken") UUID expectedClaimToken,
            @Param("newClaimToken") UUID newClaimToken);

    /**
     * For a send the scheduler could not confirm. If it lands, the consumer owns the row and this
     * must match nothing. Saving the entity instead threw on the version and rolled back every
     * other row handed back in the batch.
     */
    @Modifying
    @Query(value = "UPDATE deliveries SET status = 'PENDING', claim_token = NULL, " +
            "next_retry_at = :retryAt, updated_at = now(), version = version + 1 " +
            "WHERE id = :id AND status = 'PROCESSING' AND claim_token = :claimToken", nativeQuery = true)
    int handBackIfStillClaimed(@Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("retryAt") Instant retryAt);

    /** Matches nothing once any copy of the dispatch message has claimed the row. */
    @Modifying
    @Query(value = "UPDATE deliveries SET next_retry_at = :retryAt, updated_at = now(), version = version + 1 " +
            "WHERE id = :id AND status = 'PENDING' AND claim_token IS NULL", nativeQuery = true)
    int scheduleIfUnclaimed(@Param("id") UUID id, @Param("retryAt") Instant retryAt);

    /** Fenced, or an Attempt the stuck sweep had already reclaimed spends its successor's rung. */
    @Modifying
    @Query(value = "UPDATE deliveries SET attempt_count = attempt_count + 1, " +
            "updated_at = now(), version = version + 1 " +
            "WHERE id = :id AND claim_token IS NOT DISTINCT FROM CAST(:fence AS uuid)", nativeQuery = true)
    int incrementAttemptCount(@Param("id") UUID id, @Param("fence") UUID fence);

    /** Null when nothing in the gap is outstanding. Not used to time the gap. */
    @Query("SELECT MIN(d.createdAt) FROM Delivery d WHERE d.endpointId = :endpointId " +
            "AND d.sequenceNumber BETWEEN :rangeStart AND :rangeEnd AND d.status IN ('PENDING', 'PROCESSING')")
    Instant findOldestPendingCreatedAt(
            @Param("endpointId") UUID endpointId,
            @Param("rangeStart") long rangeStart,
            @Param("rangeEnd") long rangeEnd
    );

    /** {@code inFlightSince} stops a PROCESSING row whose worker died from holding the gap open. */
    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.endpointId = :endpointId "
            + "AND d.sequenceNumber BETWEEN :rangeStart AND :rangeEnd "
            + "AND ((d.status = 'PROCESSING' AND d.updatedAt > :inFlightSince) "
            + "OR (d.status = 'PENDING' AND (d.nextRetryAt IS NULL OR d.nextRetryAt <= :dueBy)))")
    long countGapClosingBefore(
            @Param("endpointId") UUID endpointId,
            @Param("rangeStart") long rangeStart,
            @Param("rangeEnd") long rangeEnd,
            @Param("inFlightSince") Instant inFlightSince,
            @Param("dueBy") Instant dueBy
    );

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'PENDING' AND d.createdAt > :since")
    long countPending(@Param("since") Instant since);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'PROCESSING' AND d.createdAt > :since")
    long countProcessing(@Param("since") Instant since);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'DLQ' AND d.createdAt > :since "
            + "AND d.organizationId <> :excluded")
    long countDlqExcluding(@Param("since") Instant since, @Param("excluded") UUID excludedOrganizationId);

    /** Excludes the public demo's seeded rows, which would otherwise page the operator. */
    default long countDlq(Instant since) {
        return countDlqExcluding(since, DemoTenant.ORGANIZATION_ID);
    }

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = 'DLQ' AND d.organizationId <> :excluded")
    long countDlqTotalExcluding(@Param("excluded") UUID excludedOrganizationId);

    default long countDlqTotal() {
        return countDlqTotalExcluding(DemoTenant.ORGANIZATION_ID);
    }

    @Query("SELECT MIN(d.createdAt) FROM Delivery d WHERE d.status = 'PENDING'")
    Instant findOldestPendingCreatedAtGlobal();

    /**
     * The ladder restarts at ladder_resumed_at when a person retries a Delivery; measuring from
     * created_at alone sent old retried Deliveries straight back to DLQ. The created_at predicate
     * stays so the partial index is usable.
     */
    @Query(value = """
            SELECT d.id FROM deliveries d
            WHERE d.status = 'PENDING' AND d.created_at < :cutoff
              AND (d.ladder_resumed_at IS NULL OR d.ladder_resumed_at < :cutoff)
            ORDER BY d.created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> findStaleDeliveryIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
