package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Consumer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsumerRepository extends JpaRepository<Consumer, UUID> {

    Optional<Consumer> findByIdAndProjectId(UUID id, UUID projectId);

    Page<Consumer> findByProjectId(UUID projectId, Pageable pageable);

    Page<Consumer> findByProjectIdAndExternalId(UUID projectId, String externalId, Pageable pageable);

    boolean existsByProjectIdAndExternalId(UUID projectId, String externalId);
}
