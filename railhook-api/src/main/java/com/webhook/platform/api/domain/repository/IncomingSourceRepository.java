package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncomingSourceRepository extends JpaRepository<IncomingSource, UUID> {

    Optional<IncomingSource> findByIngressPathToken(String ingressPathToken);

    Page<IncomingSource> findByProjectId(UUID projectId, Pageable pageable);

    Page<IncomingSource> findByProjectIdAndStatus(UUID projectId, IncomingSourceStatus status, Pageable pageable);

    Optional<IncomingSource> findByProjectIdAndSlug(UUID projectId, String slug);

    boolean existsByProjectIdAndSlug(UUID projectId, String slug);

    boolean existsByIngressPathToken(String ingressPathToken);

    long countByProjectId(UUID projectId);
    boolean existsByProjectId(UUID projectId);
}
