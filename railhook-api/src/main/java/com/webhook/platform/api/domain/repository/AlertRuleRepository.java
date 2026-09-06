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
     * Every enabled rule in every organization — the evaluator's entry point.
     *
     * <p>{@code AlertRule} carries {@code @TenantId}, so this returns rows from one
     * organization unless the caller is system-scoped. The scheduler that uses it declares
     * {@code @SystemTenant}; anything else calling this gets its own organization's rules,
     * which is harmless but not what the name suggests.
     */
    List<AlertRule> findByEnabledTrue();
    Optional<AlertRule> findByIdAndProjectId(UUID id, UUID projectId);
    long countByProjectId(UUID projectId);
}
