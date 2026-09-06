package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Endpoint;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
    List<Endpoint> findByProjectId(UUID projectId);
    Page<Endpoint> findByProjectIdAndDeletedAtIsNull(UUID projectId, Pageable pageable);
    long countByProjectIdAndDeletedAtIsNull(UUID projectId);
    boolean existsByProjectIdAndDeletedAtIsNull(UUID projectId);

    @Query("SELECT COUNT(e) FROM Endpoint e JOIN Project p ON e.projectId = p.id " +
           "WHERE p.organizationId = :orgId AND e.deletedAt IS NULL AND p.deletedAt IS NULL")
    long countActiveByOrganizationId(@Param("orgId") UUID organizationId);

    @Query(value = "SELECT COALESCE(MAX(cnt), 0) FROM (" +
           "SELECT COUNT(*) AS cnt FROM endpoints e JOIN projects p ON e.project_id = p.id " +
           "WHERE p.organization_id = :orgId AND e.deleted_at IS NULL AND p.deleted_at IS NULL " +
           "GROUP BY e.project_id) sub", nativeQuery = true)
    long maxEndpointsPerProjectInOrg(@Param("orgId") UUID organizationId);
}
