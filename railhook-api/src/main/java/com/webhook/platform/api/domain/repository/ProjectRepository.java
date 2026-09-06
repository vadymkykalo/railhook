package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId);

    long countByOrganizationIdAndDeletedAtIsNull(UUID organizationId);
}
