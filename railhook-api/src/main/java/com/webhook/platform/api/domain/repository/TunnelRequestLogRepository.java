package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.TunnelRequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface TunnelRequestLogRepository extends JpaRepository<TunnelRequestLog, UUID> {

    Page<TunnelRequestLog> findBySlugOrderByCreatedAtDesc(String slug, Pageable pageable);

    Page<TunnelRequestLog> findByTunnelSessionIdOrderByCreatedAtDesc(UUID tunnelSessionId, Pageable pageable);

    Page<TunnelRequestLog> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    long countBySlug(String slug);

    @Modifying
    @Query("DELETE FROM TunnelRequestLog t WHERE t.createdAt < :cutoff")
    int deleteByCreatedAtBefore(Instant cutoff);
}
