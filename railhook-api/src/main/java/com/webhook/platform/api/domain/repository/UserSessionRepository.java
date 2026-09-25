package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

/**
 * UserSession has no {@code @TenantId}, so every method must be keyed by userId or by a session
 * the caller has already been shown to own.
 */
@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    Optional<UserSession> findByRefreshTokenJti(String refreshTokenJti);

    Optional<UserSession> findByIdAndUserId(UUID id, UUID userId);

    /** Rows of {@code [userId, lastSeenAt]}. */
    @Query("SELECT s.userId, MAX(s.lastSeenAt) FROM UserSession s WHERE s.userId IN :userIds GROUP BY s.userId")
    List<Object[]> findLastSeenOfUsers(@Param("userIds") Collection<UUID> userIds);

    List<UserSession> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(
            UUID userId, Instant now);

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now "
            + "WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Revoked rows stay until they expire so the user can still see the sign-out in the list. */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
