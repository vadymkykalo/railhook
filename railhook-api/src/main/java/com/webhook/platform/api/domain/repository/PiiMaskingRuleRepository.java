package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.PiiMaskingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PiiMaskingRuleRepository extends JpaRepository<PiiMaskingRule, UUID> {
    List<PiiMaskingRule> findByProjectId(UUID projectId);
    List<PiiMaskingRule> findByProjectIdAndEnabledTrue(UUID projectId);
    Optional<PiiMaskingRule> findByProjectIdAndPatternName(UUID projectId, String patternName);
    void deleteByProjectIdAndId(UUID projectId, UUID id);
}
