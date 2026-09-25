package com.webhook.platform.api.service.rules;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Rule;
import com.webhook.platform.api.domain.entity.RuleAction;
import com.webhook.platform.api.domain.entity.RuleAction.ActionType;
import com.webhook.platform.api.domain.entity.RuleExecutionLog;
import com.webhook.platform.api.domain.repository.RuleExecutionLogRepository;
import com.webhook.platform.api.domain.repository.RuleRepository;
import com.webhook.platform.api.dto.ConditionNode;
import com.webhook.platform.common.util.EventTypeMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Rules are compiled per project and held in memory, so evaluation never hits the database. */
@Service
@Slf4j
public class RuleEngineService {

    private final RuleRepository ruleRepository;
    private final RuleExecutionLogRepository executionLogRepository;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<UUID, ProjectRulePlan> planCache = new ConcurrentHashMap<>();

    public RuleEngineService(RuleRepository ruleRepository,
                             RuleExecutionLogRepository executionLogRepository,
                             ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.executionLogRepository = executionLogRepository;
        this.objectMapper = objectMapper;
    }

    public List<RuleMatch> evaluate(UUID projectId, String eventType, JsonNode eventJson, UUID eventId) {
        ProjectRulePlan plan = planCache.get(projectId);
        if (plan == null) {
            plan = loadPlan(projectId);
        }

        Map<String, JsonNode> fieldCache = ConditionTreeEvaluator.newFieldCache();
        List<RuleMatch> matches = new ArrayList<>();

        List<CompiledRule> candidates = plan.getCandidates(eventType);

        for (CompiledRule rule : candidates) {
            long start = System.nanoTime();
            boolean matched = ConditionTreeEvaluator.evaluate(
                    rule.getConditionTree(), eventJson, fieldCache);
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            if (matched) {
                matches.add(new RuleMatch(rule, rule.getActions()));
            }

            try {
                executionLogRepository.save(RuleExecutionLog.builder()
                        .ruleId(rule.getRuleId())
                        .projectId(projectId)
                        .eventId(eventId)
                        .matched(matched)
                        .actionsExecuted(matched ? rule.getActions().size() : 0)
                        .evaluationTimeMs((int) elapsed)
                        .build());
            } catch (Exception e) {
                log.debug("Failed to save rule execution log: {}", e.getMessage());
            }
        }

        return matches;
    }

    public ProjectRulePlan loadPlan(UUID projectId) {
        List<Rule> rules = ruleRepository.findEnabledWithActions(projectId);
        List<CompiledRule> compiled = rules.stream()
                .map(this::compile)
                .collect(Collectors.toList());

        ProjectRulePlan plan = new ProjectRulePlan(compiled);
        planCache.put(projectId, plan);
        log.debug("Loaded {} rules for project {}", compiled.size(), projectId);
        return plan;
    }

    public void invalidate(UUID projectId) {
        planCache.remove(projectId);
        log.debug("Invalidated rule cache for project {}", projectId);
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${rules.cache-refresh-ms:30000}")
    public void refreshAll() {
        for (UUID projectId : planCache.keySet()) {
            try {
                loadPlan(projectId);
            } catch (Exception e) {
                log.warn("Failed to refresh rules for project {}: {}", projectId, e.getMessage());
            }
        }
    }

    private CompiledRule compile(Rule rule) {
        ConditionNode conditionTree = null;
        if (rule.getConditions() != null && !rule.getConditions().isBlank()) {
            try {
                conditionTree = objectMapper.readValue(rule.getConditions(), ConditionNode.class);
            } catch (Exception e) {
                log.warn("Failed to parse condition tree for rule {}: {}", rule.getId(), e.getMessage());
            }
        }

        List<CompiledRule.CompiledAction> actions = rule.getActions().stream()
                .sorted(Comparator.comparingInt(RuleAction::getSortOrder))
                .map(a -> CompiledRule.CompiledAction.builder()
                        .actionId(a.getId())
                        .type(a.getType())
                        .endpointId(a.getEndpointId())
                        .transformationId(a.getTransformationId())
                        .configJson(a.getConfig())
                        .sortOrder(a.getSortOrder())
                        .build())
                .collect(Collectors.toList());

        return CompiledRule.builder()
                .ruleId(rule.getId())
                .projectId(rule.getProjectId())
                .name(rule.getName())
                .priority(rule.getPriority())
                .eventTypePattern(rule.getEventTypePattern())
                .conditionTree(conditionTree)
                .actions(actions)
                .build();
    }

    public static class ProjectRulePlan {

        private final Map<String, List<CompiledRule>> exactIndex;

        private final List<CompiledRule> wildcardRules;

        private final List<CompiledRule> catchAllRules;

        public ProjectRulePlan(List<CompiledRule> rules) {
            this.exactIndex = new HashMap<>();
            this.wildcardRules = new ArrayList<>();
            this.catchAllRules = new ArrayList<>();

            for (CompiledRule rule : rules) {
                String pattern = rule.getEventTypePattern();
                if (pattern == null || pattern.isBlank()) {
                    catchAllRules.add(rule);
                } else if (EventTypeMatcher.isWildcard(pattern)) {
                    wildcardRules.add(rule);
                } else {
                    exactIndex.computeIfAbsent(pattern, k -> new ArrayList<>()).add(rule);
                }
            }
        }

        public List<CompiledRule> getCandidates(String eventType) {
            List<CompiledRule> candidates = new ArrayList<>();

            List<CompiledRule> exact = exactIndex.get(eventType);
            if (exact != null) {
                candidates.addAll(exact);
            }

            for (CompiledRule rule : wildcardRules) {
                if (EventTypeMatcher.matches(rule.getEventTypePattern(), eventType)) {
                    candidates.add(rule);
                }
            }

            candidates.addAll(catchAllRules);

            candidates.sort(Comparator.comparingInt(CompiledRule::getPriority).reversed());
            return candidates;
        }
    }

    public record RuleMatch(CompiledRule rule, List<CompiledRule.CompiledAction> actions) {

        public boolean hasDrop() {
            return actions.stream().anyMatch(a -> a.getType() == ActionType.DROP);
        }

        public List<CompiledRule.CompiledAction> getRouteActions() {
            return actions.stream().filter(a -> a.getType() == ActionType.ROUTE).toList();
        }

        public List<CompiledRule.CompiledAction> getTransformActions() {
            return actions.stream().filter(a -> a.getType() == ActionType.TRANSFORM).toList();
        }
    }
}
