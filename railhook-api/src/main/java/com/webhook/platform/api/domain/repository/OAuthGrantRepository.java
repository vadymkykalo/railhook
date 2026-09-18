package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.OAuthGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OAuthGrantRepository extends JpaRepository<OAuthGrant, UUID> {

    /**
     * Locked, so two exchanges of one code serialize: the second sees {@code code_used_at} the
     * first wrote and is treated as a replay.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthGrant> findByCodeHash(String codeHash);

    /**
     * Locked for the same reason. Under READ COMMITTED a second refresh racing on the same token
     * re-reads the row after the first commits, no longer matches, and falls through to the
     * replay check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthGrant> findByRefreshTokenHash(String refreshTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthGrant> findByPreviousRefreshTokenHash(String previousRefreshTokenHash);

    Optional<OAuthGrant> findByAccessTokenHash(String accessTokenHash);

    List<OAuthGrant> findByProjectIdAndActivatedAtIsNotNullAndRevokedAtIsNullOrderByCreatedAtDesc(UUID projectId);

    Optional<OAuthGrant> findByIdAndProjectId(UUID id, UUID projectId);

    @Modifying
    @Query("UPDATE OAuthGrant g SET g.lastUsedAt = :now WHERE g.id = :id")
    int touch(@Param("id") UUID id, @Param("now") Instant now);

    /** Approvals whose code expired without ever being exchanged: never a connection, never listed. */
    @Modifying
    @Query("DELETE FROM OAuthGrant g WHERE g.activatedAt IS NULL AND g.codeExpiresAt < :cutoff")
    int deleteUnexchanged(@Param("cutoff") Instant cutoff);
}
