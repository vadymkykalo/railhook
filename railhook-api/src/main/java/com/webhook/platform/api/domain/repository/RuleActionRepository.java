package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.RuleAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Collection;
import java.util.UUID;

@Repository
public interface RuleActionRepository extends JpaRepository<RuleAction, UUID> {

    List<RuleAction> findByRuleIdOrderBySortOrderAsc(UUID ruleId);

    List<RuleAction> findByRuleIdInOrderBySortOrderAsc(Collection<UUID> ruleIds);

    void deleteByRuleId(UUID ruleId);

    long countByEndpointId(UUID endpointId);

    long countByTransformationId(UUID transformationId);
}
