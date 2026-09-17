package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.IncomingEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncomingEventRepository extends JpaRepository<IncomingEvent, UUID>, JpaSpecificationExecutor<IncomingEvent> {

    Page<IncomingEvent> findByIncomingSourceId(UUID incomingSourceId, Pageable pageable);

    Optional<IncomingEvent> findByIncomingSourceIdAndProviderEventId(UUID incomingSourceId, String providerEventId);

    /**
     * Removes up to {@code limit} Incoming Events received before the cutoff, and through the
     * cascade their Forwards' attempt rows.
     *
     * <p>An Incoming Event with a Forward attempt still PENDING or PROCESSING is left alone however
     * old it is, as events retention leaves an in-flight Delivery. A Replay or a Failed Messages
     * retry starts a fresh Forward for a webhook that may have arrived just inside the window, and
     * the cascade wiped it mid-ladder, with a claim possibly live on it.
     */
    @Modifying
    @Query(value = """
        DELETE FROM incoming_events
         WHERE id IN (
               SELECT e.id FROM incoming_events e
                WHERE e.received_at < :cutoff
                  AND NOT EXISTS (
                      SELECT 1 FROM incoming_forward_attempts a
                       WHERE a.incoming_event_id = e.id
                         AND a.status IN ('PENDING', 'PROCESSING')
                  )
                ORDER BY e.received_at ASC
                LIMIT :limit
         )
        """, nativeQuery = true)
    int deleteOldIncomingEvents(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Query("SELECT e FROM IncomingEvent e WHERE e.incomingSourceId IN " +
            "(SELECT s.id FROM IncomingSource s WHERE s.projectId = :projectId) " +
            "ORDER BY e.receivedAt DESC")
    Page<IncomingEvent> findByProjectId(@Param("projectId") UUID projectId, Pageable pageable);

    @Query("SELECT e FROM IncomingEvent e WHERE e.incomingSourceId IN :sourceIds ORDER BY e.receivedAt DESC")
    Page<IncomingEvent> findBySourceIds(@Param("sourceIds") List<UUID> sourceIds, Pageable pageable);

    @Query("SELECT e FROM IncomingEvent e WHERE e.incomingSourceId = :sourceId " +
            "AND (:from IS NULL OR e.receivedAt >= :from) " +
            "AND (:to IS NULL OR e.receivedAt < :to) " +
            "AND (:verified IS NULL OR e.verified = :verified) " +
            "ORDER BY e.receivedAt ASC")
    List<IncomingEvent> findForBulkReplay(
            @Param("sourceId") UUID sourceId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("verified") Boolean verified,
            Pageable pageable);

    @Query("SELECT COUNT(e) FROM IncomingEvent e JOIN IncomingSource s ON e.incomingSourceId = s.id " +
            "WHERE s.projectId = :projectId AND e.receivedAt BETWEEN :from AND :to")
    long countByProjectAndDateRange(@Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COUNT(e) FROM IncomingEvent e JOIN IncomingSource s ON e.incomingSourceId = s.id " +
            "WHERE s.projectId = :projectId AND e.receivedAt >= :since")
    long countByProjectSince(@Param("projectId") UUID projectId, @Param("since") Instant since);

    @Query(value = "SELECT COALESCE(n_live_tup, 0) FROM pg_stat_user_tables WHERE relname = 'incoming_events'", nativeQuery = true)
    long estimatedRowCount();
}
