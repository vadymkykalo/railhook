package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.IncidentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    Page<Incident> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);
    Page<Incident> findByProjectIdAndStatusNotOrderByCreatedAtDesc(UUID projectId, IncidentStatus excludeStatus, Pageable pageable);
    Optional<Incident> findByIdAndProjectId(UUID id, UUID projectId);
    long countByProjectIdAndStatusNot(UUID projectId, IncidentStatus excludeStatus);

    long countByProjectIdAndStatus(UUID projectId, IncidentStatus status);

    long countByProjectIdAndSeverityAndStatusNot(UUID projectId, AlertSeverity severity,
                                                 IncidentStatus excludeStatus);
}
