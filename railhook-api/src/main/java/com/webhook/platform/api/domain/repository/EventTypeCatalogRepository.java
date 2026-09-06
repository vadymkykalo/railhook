package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.EventTypeCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventTypeCatalogRepository extends JpaRepository<EventTypeCatalog, UUID> {
    List<EventTypeCatalog> findByProjectIdOrderByNameAsc(UUID projectId);
    Optional<EventTypeCatalog> findByProjectIdAndName(UUID projectId, String name);
    boolean existsByProjectIdAndName(UUID projectId, String name);
}
