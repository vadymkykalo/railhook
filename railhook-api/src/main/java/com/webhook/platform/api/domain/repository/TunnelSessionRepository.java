package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TunnelSessionRepository extends JpaRepository<TunnelSession, UUID> {

    Optional<TunnelSession> findByTunnelToken(String tunnelToken);

    Optional<TunnelSession> findByPublicSlug(String publicSlug);

    List<TunnelSession> findByOrganizationIdAndStatus(UUID organizationId, TunnelStatus status);

    List<TunnelSession> findByUserIdAndStatus(UUID userId, TunnelStatus status);

    List<TunnelSession> findByOrganizationIdAndProjectIdAndStatus(UUID organizationId, UUID projectId, TunnelStatus status);

    Optional<TunnelSession> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationIdAndStatus(UUID organizationId, TunnelStatus status);

    /**
     * Closes the sessions whose CLI stopped sending heartbeats.
     *
     * <p>The two statuses are bound as parameters rather than written into the JPQL as
     * {@code 'EXPIRED'} and {@code 'ACTIVE'}. As string literals they were the only place either
     * value was ever written, and nothing checked them: renaming a constant on {@link TunnelStatus}
     * would have left this query storing a status the enum no longer has, and every read of those
     * rows failing on a value Hibernate cannot map — at runtime, on somebody else's schedule.
     */
    default int expireStale(Instant threshold, Instant now) {
        return updateStatusOfStaleSessions(threshold, now, TunnelStatus.ACTIVE, TunnelStatus.EXPIRED);
    }

    @Modifying
    @Query("UPDATE TunnelSession t SET t.status = :to, t.closedAt = :now " +
           "WHERE t.status = :from AND t.lastHeartbeat < :threshold")
    int updateStatusOfStaleSessions(Instant threshold, Instant now, TunnelStatus from, TunnelStatus to);
}
