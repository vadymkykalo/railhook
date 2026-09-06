package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.ApiKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHash(String keyHash);
    List<ApiKey> findByProjectIdAndRevokedAtIsNull(UUID projectId);
    Page<ApiKey> findByProjectIdAndRevokedAtIsNull(UUID projectId, Pageable pageable);
    Optional<ApiKey> findByIdAndProjectId(UUID id, UUID projectId);
    boolean existsByProjectIdAndRevokedAtIsNull(UUID projectId);
}
