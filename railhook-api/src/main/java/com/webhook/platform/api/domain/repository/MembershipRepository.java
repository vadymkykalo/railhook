package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByUserId(UUID userId);

    List<Membership> findByUserIdOrderByCreatedAtAsc(UUID userId);

    List<Membership> findByOrganizationId(UUID organizationId);

    Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    boolean existsByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    Optional<Membership> findByInviteTokenHash(String inviteTokenHash);

    long countByOrganizationIdAndRoleAndStatusNot(UUID organizationId, MembershipRole role,
            MembershipStatus status);

    long countByOrganizationId(UUID organizationId);

    /**
     * Which of these addresses belong to an active member of the organization in scope who has
     * verified the address — the only people an alert rule may email. Addresses are compared as
     * stored, which is lower case.
     */
    @Query("SELECT u.email FROM Membership m JOIN User u ON m.userId = u.id "
            + "WHERE m.status = com.webhook.platform.api.domain.enums.MembershipStatus.ACTIVE "
            + "AND u.emailVerified = true AND u.email IN :emails")
    List<String> findVerifiedMemberEmailsIn(@Param("emails") Collection<String> emails);

    @Query("SELECT m, u FROM Membership m JOIN User u ON m.userId = u.id WHERE m.organizationId = :orgId")
    List<Object[]> findMembersWithUsers(@Param("orgId") UUID organizationId);

    /**
     * {@code [organizationId, email]} of the members holding {@code role} in each organization,
     * oldest membership first — for the platform admin's list, which names each organization's
     * owner. Only meaningful in the system scope, where it can see more than one organization.
     */
    @Query("SELECT m.organizationId, u.email FROM Membership m JOIN User u ON m.userId = u.id "
            + "WHERE m.organizationId IN :organizationIds AND m.role = :role AND m.status = :status "
            + "ORDER BY m.createdAt ASC")
    List<Object[]> findEmailsByRole(@Param("organizationIds") Collection<UUID> organizationIds,
            @Param("role") MembershipRole role, @Param("status") MembershipStatus status);

    /** {@code [userId, organizationId, organizationName, role]} for each of the given accounts. */
    @Query("SELECT m.userId, o.id, o.name, m.role FROM Membership m JOIN m.organization o "
            + "WHERE m.userId IN :userIds ORDER BY m.createdAt ASC")
    List<Object[]> findOrganizationsOfUsers(@Param("userIds") Collection<UUID> userIds);

    /** The scope's members with their accounts, for a page that shows both. */
    @Query(value = "SELECT m FROM Membership m JOIN FETCH m.user",
            countQuery = "SELECT COUNT(m) FROM Membership m")
    Page<Membership> findAllWithUser(Pageable pageable);
}
