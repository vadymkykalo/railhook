package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByUserId(UUID userId);

    /**
     * A user's memberships, oldest first.
     *
     * <p>Login and refresh pick the first of these as the organization to mint the token for.
     * With the unordered {@link #findByUserId} that choice came down to whatever order the
     * database happened to return, so a user belonging to two organizations could land in a
     * different one on each login — and since {@code TenantContextFilter} derives the
     * Hibernate tenant from that claim, in a different set of data. Oldest-first at least
     * makes it the same organization every time, until there is a way to choose.</p>
     */
    List<Membership> findByUserIdOrderByCreatedAtAsc(UUID userId);

    List<Membership> findByOrganizationId(UUID organizationId);

    Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    boolean existsByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    Optional<Membership> findByInviteTokenHash(String inviteTokenHash);

    /**
     * Members holding a role whose status is not the given one.
     *
     * <p>Used for the last-owner guard, with {@code DISABLED} excluded. Counting owner rows
     * flatly would let a suspended owner stand in for an administrator who can actually sign
     * in: suspend one of two owners, then remove or demote the other, and the organization is
     * left with nobody able to administer it — including nobody able to lift the suspension.</p>
     */
    long countByOrganizationIdAndRoleAndStatusNot(UUID organizationId, MembershipRole role,
            MembershipStatus status);

    long countByOrganizationId(UUID organizationId);

    @Query("SELECT m, u FROM Membership m JOIN User u ON m.userId = u.id WHERE m.organizationId = :orgId")
    List<Object[]> findMembersWithUsers(@Param("orgId") UUID organizationId);
}
