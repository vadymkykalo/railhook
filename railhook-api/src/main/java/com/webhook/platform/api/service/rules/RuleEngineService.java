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

/**
 * High-performance rules engine with two-stage evaluation:
 * <ol>
 *   <li><b>Stage A — Prefilter (O(1))</b>: index by event_type_pattern → candidate rules</li>
 *   <li><b>Stage B — Full evaluation (O(k))</b>: evaluate compiled conditions with memoized field cache</li>
 * </ol>
 * <p>
 * Rules are loaded into memory and refreshed periodically.
 * No DB access per event evaluation.
 */
@Service
@Slf4j
public class RuleEngineService {

    private final RuleRepository ruleRepository;
    private final RuleExecutionLogRepository executionLogRepository;
    private final ObjectMapper objectMapper;

    /** projectId → compiled execution plan */
    private final ConcurrentHashMap<UUID, ProjectRulePlan> planCache = new ConcurrentHashMap<>();

    public RuleEngineService(RuleRepository ruleRepository,
                             RuleExecutionLogRepository executionLogRepository,
                             ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.executionLogRepository = executionLogRepository;
        this.objectMapper = objectMapper;
    }

    // ─── Evaluation ─────────────────────────────────────────────────────

    /**
     * Evaluate all enabled rules for a project against an event.
     *
     * @param projectId  project ID
     * @param eventType  concrete event type (e.g. "order.completed")
     * @param eventJson  raw event JSON (already parsed by caller if possible)
     * @param eventId    event ID for logging
     * @return list of matched rules with their actions
     */
    public List<RuleMatch> evaluate(UUID projectId, String eventType, JsonNode eventJson, UUID eventId) {
        ProjectRulePlan plan = planCache.get(projectId);
        if (plan == null) {
            plan = loadPlan(projectId);
        }

        Map<String, JsonNode> fieldCache = ConditionTreeEvaluator.newFieldCache();
        List<RuleMatch> matches = new ArrayList<>();

        // Stage A: prefilter — get candidate rules
        List<CompiledRule> candidates = plan.getCandidates(eventType);

        // Stage B: evaluate conditions
        for (CompiledRule rule : candidates) {
            long start = System.nanoTime();
            boolean matched = ConditionTreeEvaluator.evaluate(
                    rule.getConditionTree(), eventJson, fieldCache);
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            if (matched) {
                matches.add(new RuleMatch(rule, rule.getActions()));
            }

            // Async log (fire-and-forget, non-blocking for main pipeline)
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

    // ─── Cache management ───────────────────────────────────────────────

    /**
     * Load or reload the rule execution plan for a project.
     */
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

    /**
     * Invalidate cache for a project (called after rule CRUD).
     */
    public void invalidate(UUID projectId) {
        planCache.remove(projectId);
        log.debug("Invalidated rule cache for project {}", projectId);
    }

    /**
     * Periodic refresh — reload all cached projects every 30 seconds.
     */
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

    // ─── Compilation ────────────────────────────────────────────────────

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

    // ─── Execution Plan ─────────────────────────────────────────────────

    /**
     * In-memory execution plan for a project.
     * Two-tier index: exact event type → rules, plus catch-all list.
     */
    public static class ProjectRulePlan {

        /** Exact event type → rules (no wildcards) */
        private final Map<String, List<CompiledRule>> exactIndex;

        /** Wildcard rules (patterns with * or **) */
        private final List<CompiledRule> wildcardRules;

        /** Catch-all rules (no event_type_pattern) */
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

        /**
         * Get candidate rules for a concrete event type.
         * O(1) for exact matches + O(w) for wildcard rules + catch-all.
         */
        public List<CompiledRule> getCandidates(String eventType) {
            List<CompiledRule> candidates = new ArrayList<>();

            // 1. Exact match
            List<CompiledRule> exact = exactIndex.get(eventType);
            if (exact != null) {
                candidates.addAll(exact);
            }

            // 2. Wildcard rules
            for (CompiledRule rule : wildcardRules) {
                if (EventTypeMatcher.matches(rule.getEventTypePattern(), eventType)) {
                    candidates.add(rule);
                }
            }

            // 3. Catch-all
            candidates.addAll(catchAllRules);

            // Sort by priority (highest first)
            candidates.sort(Comparator.comparingInt(CompiledRule::getPriority).reversed());
            return candidates;
        }
    }

    // ─── Result types ───────────────────────────────────────────────────

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
