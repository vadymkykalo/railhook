package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.TestEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestEndpointRepository extends JpaRepository<TestEndpoint, UUID> {

    Optional<TestEndpoint> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<TestEndpoint> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    @Modifying
    @Query("DELETE FROM TestEndpoint t WHERE t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    @Query("SELECT COUNT(t) FROM TestEndpoint t WHERE t.projectId = :projectId")
    long countByProjectId(@Param("projectId") UUID projectId);
}
