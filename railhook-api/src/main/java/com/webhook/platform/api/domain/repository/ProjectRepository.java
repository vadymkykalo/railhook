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

    /**
     * Just enough of a project to bill it: its id, and the organization whose scope has to be
     * entered before its rows can be read.
     *
     * <p>A projection rather than the entity, because the nightly sweep visits every project on
     * the platform and needs neither the name, the description, nor the schema-validation
     * settings hanging off each one.
     */
    interface ProjectRef {
        UUID getId();

        UUID getOrganizationId();
    }

    /**
     * One page of the platform's live projects, for a job that walks all of them.
     *
     * <p>Ordered by id so successive pages do not overlap or skip — an unordered page is
     * whatever the planner felt like returning, which for a sweep means some projects twice and
     * some never.
     *
     * <p>Deleted projects are excluded. {@code findAll()} did not exclude them, so every
     * soft-deleted project was still being counted and written a usage row every night, forever.
     */
    @Query("SELECT p.id AS id, p.organizationId AS organizationId FROM Project p "
            + "WHERE p.deletedAt IS NULL ORDER BY p.id")
    List<ProjectRef> findLiveRefs(Pageable pageable);
}
