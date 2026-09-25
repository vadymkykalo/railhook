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
    // Addresses are stored normalized, so pass EmailAddresses.normalize(address).
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<User> findByVerificationToken(String verificationToken);
    Optional<User> findByPasswordResetToken(String passwordResetToken);

    long countByCreatedAtGreaterThanEqual(Instant since);

    long countByIdNot(UUID excludedId);

    Page<User> findByIdNot(UUID excludedId, Pageable pageable);

    long countByCreatedAtGreaterThanEqualAndEmailVerifiedTrue(Instant since);

    @Query("SELECT CAST(u.createdAt AS LocalDate), COUNT(u) FROM User u WHERE u.createdAt >= :since "
            + "GROUP BY CAST(u.createdAt AS LocalDate)")
    List<Object[]> countPerDaySince(@Param("since") Instant since);

    @Query("SELECT u FROM User u WHERE :search IS NULL "
            + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) "
            + "OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))")
    Page<User> searchForOperator(@Param("search") String search, Pageable pageable);

    /**
     * Crosses every organization on purpose, so it runs only under {@code @SystemTenant}; the
     * organization_id references are joins, not a tenant predicate. Events are reached through
     * projects and sources because that is how they are indexed.
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

    // Own transaction: the nudge job has none open and sends after each claim.
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.onboardingNudgeSentAt = :sentAt WHERE u.id = :id AND u.onboardingNudgeSentAt IS NULL")
    int markOnboardingNudgeSent(@Param("id") UUID id, @Param("sentAt") Instant sentAt);
}
