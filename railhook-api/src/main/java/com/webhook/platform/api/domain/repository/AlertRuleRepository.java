package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {
    List<AlertRule> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<AlertRule> findByProjectIdAndEnabledTrue(UUID projectId);

    /**
     * Crosses organizations only under {@code @SystemTenant}, as the evaluator runs it. Any other
     * caller gets just its own organization's rules.
     */
    List<AlertRule> findByEnabledTrue();
    Optional<AlertRule> findByIdAndProjectId(UUID id, UUID projectId);
    long countByProjectId(UUID projectId);
}
