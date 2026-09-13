package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /**
     * Loads an Organization with its Plan already fetched.
     *
     * <p>{@code Organization.plan} is LAZY, and with Open Session In View off, a proxy
     * returned to a caller outside the transaction cannot be initialised. EntitlementService caches
     * the Plan and hands it to request handlers, so it has to be a real object by the time the
     * transaction ends, not a proxy that fails on first use.
     */
    @Query("SELECT o FROM Organization o JOIN FETCH o.plan WHERE o.id = :id")
    Optional<Organization> findByIdWithPlan(@Param("id") UUID id);

    long countBySuspendedAtIsNotNull();

    /** {@code [organizationId, maxEventsPerMonth]} of each given organization's plan. */
    @Query("SELECT o.id, p.maxEventsPerMonth FROM Organization o JOIN o.plan p WHERE o.id IN :organizationIds")
    java.util.List<Object[]> findEventLimits(@Param("organizationIds") java.util.Collection<UUID> organizationIds);

    /**
     * The operator's listing: every organization, newest first, optionally narrowed by name.
     *
     * <p>Joins the plan because the listing shows it and the association is LAZY — a page of
     * proxies resolved outside the transaction is the N+1 this avoids twice over.
     *
     * <p>Unscoped by design. Nothing about this query belongs to one tenant, which is the whole
     * point of a back-office; the caller is the platform-admin credential, which has no
     * organization of its own.
     */
    @Query("SELECT o FROM Organization o JOIN FETCH o.plan "
            + "WHERE (:search IS NULL OR LOWER(o.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) "
            // Or by a member's address: "which organization is ops@customer.com in" is the
            // question a support ticket arrives with, and it names a person, not an organization.
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
