package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.IncomingDestination;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface IncomingDestinationRepository extends JpaRepository<IncomingDestination, UUID> {

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
}
