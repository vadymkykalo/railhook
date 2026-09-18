package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.PortalSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link #findByTokenHash} is the authentication lookup, made before any tenant is known and so
 * under the system scope — the hash of a token only its holder has is what confines it.
 */
@Repository
public interface PortalSessionRepository extends JpaRepository<PortalSession, UUID> {

    Optional<PortalSession> findByTokenHash(String tokenHash);

    @Modifying
    @Query("DELETE FROM PortalSession s WHERE s.consumerId = :consumerId")
    int deleteByConsumerId(@Param("consumerId") UUID consumerId);

    @Modifying
    @Query("DELETE FROM PortalSession s WHERE s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
