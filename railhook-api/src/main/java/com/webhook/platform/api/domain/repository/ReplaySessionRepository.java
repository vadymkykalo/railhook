package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.ReplaySession;
import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReplaySessionRepository extends JpaRepository<ReplaySession, UUID> {

    Page<ReplaySession> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);

    long countByProjectIdAndStatusIn(UUID projectId, List<ReplaySessionStatus> statuses);

    Optional<ReplaySession> findByIdAndProjectId(UUID id, UUID projectId);

    @Modifying
    @Query("UPDATE ReplaySession r SET r.status = :newStatus, r.cancelledAt = CURRENT_TIMESTAMP " +
           "WHERE r.id = :id AND r.status IN :allowedStatuses")
    int cancelSession(@Param("id") UUID id,
                      @Param("newStatus") ReplaySessionStatus newStatus,
                      @Param("allowedStatuses") List<ReplaySessionStatus> allowedStatuses);

    /**
     * Conditional so a late launch, after a cancel or after {@link #failStaleSessions}, does not
     * resurrect the session.
     */
    @Modifying
    @Query("UPDATE ReplaySession r SET r.status = :running, r.startedAt = :now, r.updatedAt = :now " +
           "WHERE r.id = :id AND r.status = :pending")
    int markStarted(@Param("id") UUID id,
                    @Param("pending") ReplaySessionStatus pending,
                    @Param("running") ReplaySessionStatus running,
                    @Param("now") Instant now);

    /**
     * A running replay writes {@code updated_at} after every batch, so an active row untouched
     * since {@code cutoff} has no runner on any replica.
     */
    @Modifying
    @Query("UPDATE ReplaySession r SET r.status = :failed, r.errorMessage = :message, " +
           "r.completedAt = :now, r.updatedAt = :now " +
           "WHERE r.status IN :active AND r.updatedAt < :cutoff")
    int failStaleSessions(@Param("active") List<ReplaySessionStatus> active,
                          @Param("failed") ReplaySessionStatus failed,
                          @Param("message") String message,
                          @Param("cutoff") Instant cutoff,
                          @Param("now") Instant now);
}
