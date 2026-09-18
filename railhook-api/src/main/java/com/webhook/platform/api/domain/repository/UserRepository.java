package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    /** Addresses are stored normalized, so pass {@code EmailAddresses.normalize(address)}. */
    Optional<User> findByEmail(String email);
    /** Addresses are stored normalized, so pass {@code EmailAddresses.normalize(address)}. */
    boolean existsByEmail(String email);
    Optional<User> findByVerificationToken(String verificationToken);
    Optional<User> findByPasswordResetToken(String passwordResetToken);

    long countByCreatedAtGreaterThanEqual(Instant since);

    long countByCreatedAtGreaterThanEqualAndEmailVerifiedTrue(Instant since);

    /** {@code [day, count]} of accounts created since then, one row per calendar day that has any. */
    @Query("SELECT CAST(u.createdAt AS LocalDate), COUNT(u) FROM User u WHERE u.createdAt >= :since "
            + "GROUP BY CAST(u.createdAt AS LocalDate)")
    List<Object[]> countPerDaySince(@Param("since") Instant since);

    /** The platform admin's account search: by address or name, a null term matching everyone. */
    @Query("SELECT u FROM User u WHERE :search IS NULL "
            + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) "
            + "OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))")
    Page<User> searchForOperator(@Param("search") String search, Pageable pageable);

    /**
     * Accounts due the day-2 onboarding nudge, oldest welcome first: verified and active, welcomed
     * before {@code welcomedBefore}, not nudged yet, owning an organization that is not suspended,
     * and a member of no organization that has sent an Event or received an Incoming Event.
     *
     * <p>Crosses every organization on purpose, so it runs only under {@code @SystemTenant} from
     * {@code OnboardingMailService}; the organization_id references below are joins, not a tenant
     * predicate. Events are reached through projects because events and incoming_events are
     * indexed by project and by source, not by organization.
     */
    @Query(value = """
            SELECT u.id FROM users u
            WHERE u.onboarding_nudge_sent_at IS NULL
              AND u.onboarding_welcome_sent_at <= :welcomedBefore
              AND u.email_verified = TRUE
              AND u.status = 'ACTIVE'
              AND EXISTS (SELECT 1 FROM memberships m
                          JOIN organizations o ON o.id = m.organization_id
                          WHERE m.user_id = u.id AND m.role = 'OWNER' AND m.status = 'ACTIVE'
                            AND o.suspended_at IS NULL)
              AND NOT EXISTS (SELECT 1 FROM memberships m
                              JOIN projects p ON p.organization_id = m.organization_id
                              JOIN events e ON e.project_id = p.id
                              WHERE m.user_id = u.id)
              AND NOT EXISTS (SELECT 1 FROM memberships m
                              JOIN incoming_sources s ON s.organization_id = m.organization_id
                              JOIN incoming_events ie ON ie.incoming_source_id = s.id
                              WHERE m.user_id = u.id)
            ORDER BY u.onboarding_welcome_sent_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDueOnboardingNudges(@Param("welcomedBefore") Instant welcomedBefore, @Param("limit") int limit);

    /**
     * Records the nudge as sent, unless it already was; 1 when this call claimed it. Transactional
     * on its own account: the nudge job runs with no transaction open and sends after each claim.
     */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.onboardingNudgeSentAt = :sentAt WHERE u.id = :id AND u.onboardingNudgeSentAt IS NULL")
    int markOnboardingNudgeSent(@Param("id") UUID id, @Param("sentAt") Instant sentAt);
}
