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

/**
 * Every method here is keyed by {@code userId} or by a session the caller has already been shown
 * to own — {@code UserSession} carries no {@code @TenantId}, so nothing else confines these
 * reads. See the entity for why it is not tenant-scoped.
 */
@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    Optional<UserSession> findByRefreshTokenJti(String refreshTokenJti);

    Optional<UserSession> findByIdAndUserId(UUID id, UUID userId);

    List<UserSession> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(
            UUID userId, Instant now);

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now "
            + "WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Drops rows whose refresh token has expired outright. Revoked-but-unexpired rows stay until
     * their expiry, because a person looking at the list right after signing a device out should
     * see that it happened rather than see it silently vanish; {@code expires_at} then removes
     * them on the same schedule as everything else.
     */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
