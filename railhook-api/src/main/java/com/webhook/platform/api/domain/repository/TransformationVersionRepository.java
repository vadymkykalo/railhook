package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.TransformationVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rows are deleted only by the retention cap in {@code TransformationService} and, at the
 * database, by the {@code ON DELETE CASCADE} from the transformation itself.
 */
@Repository
public interface TransformationVersionRepository extends JpaRepository<TransformationVersion, UUID> {

    /** One transformation's history, newest first — both what the list shows and what the cap trims. */
    List<TransformationVersion> findByTransformationIdOrderByVersionDesc(UUID transformationId);

    Optional<TransformationVersion> findByTransformationIdAndVersion(UUID transformationId, Integer version);
}
