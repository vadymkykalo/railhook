package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.TransformationVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransformationVersionRepository extends JpaRepository<TransformationVersion, UUID> {

    List<TransformationVersion> findByTransformationIdOrderByVersionDesc(UUID transformationId);

    Optional<TransformationVersion> findByTransformationIdAndVersion(UUID transformationId, Integer version);

    /** Numbers only, so the retention cap does not load every template (up to 64 KB each). */
    @Query("select v.version from TransformationVersion v "
            + "where v.transformationId = :transformationId order by v.version desc")
    List<Integer> findVersionNumbersDesc(@Param("transformationId") UUID transformationId);

    List<TransformationVersion> findByTransformationIdAndVersionLessThan(UUID transformationId, Integer version);
}
