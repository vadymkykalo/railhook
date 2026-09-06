package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.SchemaChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

@Repository
public interface SchemaChangeRepository extends JpaRepository<SchemaChange, UUID> {
    List<SchemaChange> findByEventTypeIdOrderByCreatedAtDesc(UUID eventTypeId);
    List<SchemaChange> findByToVersionId(UUID toVersionId);

    @Query("SELECT sc FROM SchemaChange sc JOIN FETCH sc.eventType et " +
           "LEFT JOIN FETCH sc.fromVersion LEFT JOIN FETCH sc.toVersion " +
           "WHERE et.projectId = :projectId ORDER BY sc.createdAt DESC")
    List<SchemaChange> findByProjectIdWithDetails(@Param("projectId") UUID projectId);
}
