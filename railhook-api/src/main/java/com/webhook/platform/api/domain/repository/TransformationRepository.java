package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Transformation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransformationRepository extends JpaRepository<Transformation, UUID> {
    List<Transformation> findByProjectIdOrderByNameAsc(UUID projectId);
    List<Transformation> findByProjectIdAndEnabledTrueOrderByNameAsc(UUID projectId);
    boolean existsByProjectIdAndName(UUID projectId, String name);
    boolean existsByProjectIdAndNameAndIdNot(UUID projectId, String name, UUID id);
}
