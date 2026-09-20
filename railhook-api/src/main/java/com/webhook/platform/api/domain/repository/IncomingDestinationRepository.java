package com.webhook.platform.api.domain.repository;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Modifying;
import java.time.Instant;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncomingDestinationRepository extends JpaRepository<IncomingDestination, UUID> {

    Optional<IncomingDestination> findByIdAndIncomingSourceId(UUID id, UUID incomingSourceId);

    List<IncomingDestination> findByIncomingSourceId(UUID incomingSourceId);

    List<IncomingDestination> findByIncomingSourceIdAndEnabledTrue(UUID incomingSourceId);

    Page<IncomingDestination> findByIncomingSourceId(UUID incomingSourceId, Pageable pageable);

    @Query("SELECT COUNT(d) FROM IncomingDestination d JOIN IncomingSource s ON d.incomingSourceId = s.id WHERE s.projectId = :projectId")
    long countByProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM IncomingDestination d JOIN IncomingSource s ON d.incomingSourceId = s.id WHERE s.projectId = :projectId")
    boolean existsByProjectId(@Param("projectId") UUID projectId);

    long countByTransformationId(UUID transformationId);

    /** Counts for a whole page at once, so listing does not run one query per row. */
    @Query("SELECT d.transformationId, COUNT(d) FROM IncomingDestination d "
            + "WHERE d.transformationId IN :transformationIds GROUP BY d.transformationId")
    List<Object[]> countByTransformationIds(@Param("transformationIds") Collection<UUID> transformationIds);

    /** @see EndpointRepository#findAutoDisableCandidates — the same sweep, the other target. */
    @Query("SELECT d FROM IncomingDestination d WHERE d.enabled = true "
            + "AND d.failingSince IS NOT NULL AND d.failingSince < :cutoff "
            + "AND d.consecutiveFailures >= :minFailures ORDER BY d.failingSince ASC")
    List<IncomingDestination> findAutoDisableCandidates(@Param("cutoff") Instant cutoff,
            @Param("minFailures") int minFailures, Pageable pageable);

    /** @see EndpointRepository#autoDisable — the same conditional update on the other target. */
    @Modifying
    @Transactional
    @Query("UPDATE IncomingDestination d SET d.enabled = false, d.autoDisabledAt = :at, "
            + "d.autoDisabledReason = :reason "
            + "WHERE d.id = :id AND d.enabled = true AND d.autoDisabledAt IS NULL")
    int autoDisable(@Param("id") UUID id, @Param("at") Instant at, @Param("reason") String reason);
}
