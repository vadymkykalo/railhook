package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Project;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId);

    long countByOrganizationIdAndDeletedAtIsNull(UUID organizationId);

    @Query("SELECT p FROM Project p WHERE p.deletedAt IS NULL")
    org.springframework.data.domain.Page<Project> findLive(Pageable pageable);

    /** The organization id is the scope a job must enter before it can read the project's rows. */
    interface ProjectRef {
        UUID getId();

        UUID getOrganizationId();
    }

    /** Ordered by id so successive pages neither overlap nor skip. */
    @Query("SELECT p.id AS id, p.organizationId AS organizationId FROM Project p "
            + "WHERE p.deletedAt IS NULL ORDER BY p.id")
    List<ProjectRef> findLiveRefs(Pageable pageable);
}
