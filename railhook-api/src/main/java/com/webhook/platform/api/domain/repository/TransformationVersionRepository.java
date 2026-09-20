package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.TransformationVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** One transformation's history, newest first — what the history view lists. */
    List<TransformationVersion> findByTransformationIdOrderByVersionDesc(UUID transformationId);

    Optional<TransformationVersion> findByTransformationIdAndVersion(UUID transformationId, Integer version);

    /**
     * The version numbers alone, newest first. The retention cap needs to know how many versions
     * there are and where the cut falls, and nothing else: loading the rows would pull every
     * stored template — up to 64 KB each — through the session to decide which one row to drop.
     */
    @Query("select v.version from TransformationVersion v "
            + "where v.transformationId = :transformationId order by v.version desc")
    List<Integer> findVersionNumbersDesc(@Param("transformationId") UUID transformationId);

    List<TransformationVersion> findByTransformationIdAndVersionLessThan(UUID transformationId, Integer version);
}
