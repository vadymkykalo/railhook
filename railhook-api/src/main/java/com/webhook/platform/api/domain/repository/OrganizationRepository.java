package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /** The Plan is cached and used outside the transaction, so it must not be a lazy proxy. */
    @Query("SELECT o FROM Organization o JOIN FETCH o.plan WHERE o.id = :id")
    Optional<Organization> findByIdWithPlan(@Param("id") UUID id);

    /**
     * Serializes count-then-insert limit checks: without the lock, two requests could both count
     * before either inserts and both see room.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Organization o WHERE o.id = :id")
    Optional<Organization> lockById(@Param("id") UUID id);

    long countByIdNot(UUID excludedId);

    long countBySuspendedAtIsNotNullAndIdNot(UUID excludedId);

    long countByCreatedAtGreaterThanEqual(Instant since);

    /** Counts deleted projects too. */
    @Query("SELECT COUNT(o) FROM Organization o WHERE o.createdAt >= :since "
            + "AND EXISTS (SELECT 1 FROM Project p WHERE p.organizationId = o.id)")
    long countCreatedSinceWithProject(@Param("since") Instant since);

    @Query("SELECT COUNT(o) FROM Organization o WHERE o.createdAt >= :since "
            + "AND EXISTS (SELECT 1 FROM Event e WHERE e.organizationId = o.id)")
    long countCreatedSinceWithEvent(@Param("since") Instant since);

    /** Rows of {@code [organizationId, maxEventsPerMonth]}. */
    @Query("SELECT o.id, p.maxEventsPerMonth FROM Organization o JOIN o.plan p WHERE o.id IN :organizationIds")
    java.util.List<Object[]> findEventLimits(@Param("organizationIds") java.util.Collection<UUID> organizationIds);

    /**
     * Unscoped by design: the platform-admin caller has no organization of its own. Search also
     * matches a member's email, since support tickets name a person.
     */
    @Query("SELECT o FROM Organization o JOIN FETCH o.plan "
            + "WHERE (:search IS NULL OR LOWER(o.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) "
            + "OR EXISTS (SELECT 1 FROM Membership m JOIN User u ON m.userId = u.id "
            + "WHERE m.organizationId = o.id "
            + "AND LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))) "
            + "AND (:suspendedOnly = FALSE OR o.suspendedAt IS NOT NULL)")
    Page<Organization> searchForOperator(@Param("search") String search,
            @Param("suspendedOnly") boolean suspendedOnly,
            Pageable pageable);

    @Modifying
    @Query("UPDATE Organization o SET o.plan = :plan WHERE o.plan <> :plan")
    int bulkAssignPlan(@Param("plan") Plan plan);
}
