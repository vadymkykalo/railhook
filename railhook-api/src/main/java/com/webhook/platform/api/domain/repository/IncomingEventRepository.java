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

    @Modifying
    @Query(value = "DELETE FROM incoming_events WHERE id IN (SELECT id FROM incoming_events WHERE received_at < :cutoff ORDER BY received_at ASC LIMIT :limit)", nativeQuery = true)
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
