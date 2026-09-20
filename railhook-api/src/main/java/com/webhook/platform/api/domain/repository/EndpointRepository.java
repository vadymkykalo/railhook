package com.webhook.platform.api.domain.repository;

import java.time.Instant;
import com.webhook.platform.api.domain.entity.Endpoint;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    Optional<Endpoint> findByIdAndProjectId(UUID id, UUID projectId);

    List<Endpoint> findByProjectId(UUID projectId);
    Page<Endpoint> findByProjectIdAndDeletedAtIsNull(UUID projectId, Pageable pageable);
    long countByProjectIdAndDeletedAtIsNull(UUID projectId);

    List<Endpoint> findByConsumerIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID consumerId);

    Optional<Endpoint> findByIdAndConsumerIdAndDeletedAtIsNull(UUID id, UUID consumerId);

    /** Deleted ones included: their Deliveries are still the Consumer's history. */
    @Query("SELECT e.id FROM Endpoint e WHERE e.consumerId = :consumerId")
    List<UUID> findIdsByConsumerId(@Param("consumerId") UUID consumerId);

    /** {@code [consumerId, count]} of live Endpoints, for each of the given Consumers that has one. */
    @Query("SELECT e.consumerId, COUNT(e) FROM Endpoint e "
            + "WHERE e.consumerId IN :consumerIds AND e.deletedAt IS NULL GROUP BY e.consumerId")
    List<Object[]> countLiveByConsumerIds(@Param("consumerIds") Collection<UUID> consumerIds);
    boolean existsByProjectIdAndDeletedAtIsNull(UUID projectId);

    @Query("SELECT COUNT(e) FROM Endpoint e JOIN Project p ON e.projectId = p.id " +
           "WHERE p.organizationId = :orgId AND e.deletedAt IS NULL AND p.deletedAt IS NULL")
    long countActiveByOrganizationId(@Param("orgId") UUID organizationId);

    @Query(value = "SELECT COALESCE(MAX(cnt), 0) FROM (" +
           "SELECT COUNT(*) AS cnt FROM endpoints e JOIN projects p ON e.project_id = p.id " +
           "WHERE p.organization_id = :orgId AND e.deleted_at IS NULL AND p.deleted_at IS NULL " +
           "GROUP BY e.project_id) sub", nativeQuery = true)
    long maxEndpointsPerProjectInOrg(@Param("orgId") UUID organizationId);

    /**
     * Endpoints the auto-disable sweep should look at: live, still on, and in an unbroken run of
     * failures that both started before {@code cutoff} and is at least {@code minFailures} long.
     *
     * <p>Both conditions are the query's, not a filter applied to its results: a near-idle
     * endpoint that failed once three days ago has a {@code failing_since} as old as a dead one's,
     * and the count is the only thing that tells them apart.
     */
    @Query("SELECT e FROM Endpoint e WHERE e.enabled = true AND e.deletedAt IS NULL "
            + "AND e.failingSince IS NOT NULL AND e.failingSince < :cutoff "
            + "AND e.consecutiveFailures >= :minFailures ORDER BY e.failingSince ASC")
    List<Endpoint> findAutoDisableCandidates(@Param("cutoff") Instant cutoff,
            @Param("minFailures") int minFailures, Pageable pageable);
}
