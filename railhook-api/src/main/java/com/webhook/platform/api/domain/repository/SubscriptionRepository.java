package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    List<Subscription> findByProjectIdAndEventTypeAndEnabledTrue(UUID projectId, String eventType);
    List<Subscription> findByProjectIdAndEnabledTrue(UUID projectId);
    List<Subscription> findByProjectId(UUID projectId);
    boolean existsByProjectId(UUID projectId);
    long countByTransformationId(UUID transformationId);

    /** Counts for a whole page at once, so listing does not run one query per row. */
    @Query("SELECT s.transformationId, COUNT(s) FROM Subscription s "
            + "WHERE s.transformationId IN :transformationIds GROUP BY s.transformationId")
    List<Object[]> countByTransformationIds(@Param("transformationIds") Collection<UUID> transformationIds);
    boolean existsByEndpointIdAndEventType(UUID endpointId, String eventType);

    @Query("SELECT s FROM Subscription s WHERE s.projectId = :projectId AND s.enabled = true " +
           "AND s.eventType LIKE '%*%'")
    List<Subscription> findWildcardSubscriptions(@Param("projectId") UUID projectId);
}
