package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.Rule;
import com.webhook.platform.api.domain.entity.RuleAction;
import com.webhook.platform.api.domain.entity.RuleAction.ActionType;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.*;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.service.rules.RuleEngineService;
import com.webhook.platform.common.util.EventTypeMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RuleService {

    private final RuleRepository ruleRepository;
    private final RuleActionRepository ruleActionRepository;
    private final RuleExecutionLogRepository executionLogRepository;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final TransformationRepository transformationRepository;
    private final RuleEngineService ruleEngineService;
    private final ObjectMapper objectMapper;

    /**
     * Turns "no such project here" into a 404. {@code Project} carries {@code @TenantId}, so this
     * lookup only sees projects inside the caller's organization: a foreign project id is
     * indistinguishable from a missing one, which is intended.
     */
    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "Rule")
    @Transactional
    public RuleResponse create(UUID projectId, RuleRequest request) {
        validateProjectOwnership(projectId);

        if (ruleRepository.existsByProjectIdAndName(projectId, request.getName())) {
            throw new ConflictException("Rule with this name already exists");
        }

        if (request.getEventTypePattern() != null && !request.getEventTypePattern().isBlank()) {
            if (!EventTypeMatcher.isValidPattern(request.getEventTypePattern())) {
                throw new IllegalArgumentException("Invalid event type pattern: " + request.getEventTypePattern());
            }
        }

        String conditionsJson = serializeConditions(request.getConditions());

        Rule rule = Rule.builder()
                .projectId(projectId)
                .name(request.getName())
                .description(request.getDescription())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .eventTypePattern(request.getEventTypePattern())
                .conditions(conditionsJson)
                .build();

        rule = ruleRepository.saveAndFlush(rule);

        if (request.getActions() != null && !request.getActions().isEmpty()) {
            saveActions(rule.getId(), projectId, request.getActions());
        }

        ruleEngineService.invalidate(projectId);
        log.info("Created rule '{}' for project {}", rule.getName(), projectId);
        return mapToResponse(rule);
    }

    public RuleResponse get(UUID id) {
        Rule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Rule not found"));
        validateProjectOwnership(rule.getProjectId());
        return mapToResponse(rule);
    }

    public List<RuleResponse> list(UUID projectId) {
        validateProjectOwnership(projectId);
        List<Rule> rules = ruleRepository.findByProjectIdOrderByPriorityDescCreatedAtDesc(projectId);
        Set<UUID> ruleIds = rules.stream().map(Rule::getId).collect(Collectors.toSet());

        Map<UUID, List<RuleAction>> actionsByRule = ruleActionRepository
                .findByRuleIdInOrderBySortOrderAsc(ruleIds).stream()
                .collect(Collectors.groupingBy(RuleAction::getRuleId));
        Map<UUID, ExecutionCounts> countsByRule = executionCountsFor(ruleIds);

        return rules.stream()
                .map(rule -> mapToResponse(rule,
                        actionsByRule.getOrDefault(rule.getId(), List.of()),
                        countsByRule.getOrDefault(rule.getId(), ExecutionCounts.NONE)))
                .collect(Collectors.toList());
    }

    /** How often a rule ran, and how often it matched. */
    private record ExecutionCounts(long executions, long matches) {

        static final ExecutionCounts NONE = new ExecutionCounts(0, 0);
    }

    private Map<UUID, ExecutionCounts> executionCountsFor(Set<UUID> ruleIds) {
        return executionLogRepository.countByRuleIds(ruleIds).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> new ExecutionCounts((Long) row[1], row[2] == null ? 0L : ((Number) row[2]).longValue())));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "Rule")
    @Transactional
    public RuleResponse update(UUID id, RuleRequest request) {
        Rule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Rule not found"));
        validateProjectOwnership(rule.getProjectId());

        if (request.getName() != null) {
            if (!rule.getName().equals(request.getName()) &&
                    ruleRepository.existsByProjectIdAndName(rule.getProjectId(), request.getName())) {
                throw new ConflictException("Rule with this name already exists");
            }
            rule.setName(request.getName());
        }
        if (request.getDescription() != null) {
            rule.setDescription(request.getDescription());
        }
        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }
        if (request.getPriority() != null) {
            rule.setPriority(request.getPriority());
        }
        if (request.getEventTypePattern() != null) {
            if (!request.getEventTypePattern().isBlank() &&
                    !EventTypeMatcher.isValidPattern(request.getEventTypePattern())) {
                throw new IllegalArgumentException("Invalid event type pattern: " + request.getEventTypePattern());
            }
            rule.setEventTypePattern(request.getEventTypePattern().isBlank() ? null : request.getEventTypePattern());
        }
        if (request.getConditions() != null) {
            rule.setConditions(serializeConditions(request.getConditions()));
        }

        rule = ruleRepository.saveAndFlush(rule);

        if (request.getActions() != null) {
            ruleActionRepository.deleteByRuleId(id);
            ruleActionRepository.flush();
            saveActions(id, rule.getProjectId(), request.getActions());
        }

        ruleEngineService.invalidate(rule.getProjectId());
        log.info("Updated rule '{}' ({})", rule.getName(), id);
        return mapToResponse(rule);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "Rule")
    @Transactional
    public void delete(UUID id) {
        Rule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Rule not found"));
        validateProjectOwnership(rule.getProjectId());

        UUID projectId = rule.getProjectId();
        ruleRepository.deleteById(id);
        ruleEngineService.invalidate(projectId);
        log.info("Deleted rule '{}' ({})", rule.getName(), id);
    }

    @Transactional
    public RuleResponse toggleEnabled(UUID id, boolean enabled) {
        Rule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Rule not found"));
        validateProjectOwnership(rule.getProjectId());

        rule.setEnabled(enabled);
        rule = ruleRepository.saveAndFlush(rule);
        ruleEngineService.invalidate(rule.getProjectId());
        return mapToResponse(rule);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private void saveActions(UUID ruleId, UUID projectId, List<RuleActionRequest> actions) {
        for (int i = 0; i < actions.size(); i++) {
            RuleActionRequest actionReq = actions.get(i);

            ActionType actionType = actionReq.getType();

            // Validate endpoint exists AND belongs to same project for ROUTE
            if (actionType == ActionType.ROUTE) {
                if (actionReq.getEndpointId() == null) {
                    throw new IllegalArgumentException("ROUTE action requires endpointId");
                }
                Endpoint endpoint = endpointRepository.findById(actionReq.getEndpointId())
                        .orElseThrow(() -> new NotFoundException("Endpoint not found for ROUTE action"));
                if (!endpoint.getProjectId().equals(projectId)) {
                    throw new ForbiddenException("Endpoint does not belong to this project");
                }
            }

            // Validate transformation exists AND belongs to same project for TRANSFORM
            if (actionType == ActionType.TRANSFORM) {
                if (actionReq.getTransformationId() == null) {
                    throw new IllegalArgumentException("TRANSFORM action requires transformationId");
                }
                Transformation transformation = transformationRepository.findById(actionReq.getTransformationId())
                        .orElseThrow(() -> new NotFoundException("Transformation not found for TRANSFORM action"));
                if (!transformation.getProjectId().equals(projectId)) {
                    throw new ForbiddenException("Transformation does not belong to this project");
                }
            }

            String configJson;
            try {
                configJson = actionReq.getConfig() != null
                        ? objectMapper.writeValueAsString(actionReq.getConfig())
                        : "{}";
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid action config JSON");
            }

            ruleActionRepository.save(RuleAction.builder()
                    .ruleId(ruleId)
                    .type(actionType)
                    .endpointId(actionReq.getEndpointId())
                    .transformationId(actionReq.getTransformationId())
                    .config(configJson)
                    .sortOrder(actionReq.getSortOrder() != null ? actionReq.getSortOrder() : i)
                    .build());
        }
    }

    private String serializeConditions(ConditionNode conditions) {
        if (conditions == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(conditions);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize conditions: " + e.getMessage());
        }
    }

    private ConditionNode parseConditions(Rule rule) {
        if (rule.getConditions() == null || rule.getConditions().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rule.getConditions(), ConditionNode.class);
        } catch (Exception e) {
            log.warn("Failed to parse condition tree for rule {}: {}", rule.getId(), e.getMessage());
            return null;
        }
    }

    private RuleResponse mapToResponse(Rule rule) {
        return mapToResponse(rule,
                ruleActionRepository.findByRuleIdOrderBySortOrderAsc(rule.getId()),
                new ExecutionCounts(
                        executionLogRepository.countByRuleId(rule.getId()),
                        executionLogRepository.countByRuleIdAndMatchedTrue(rule.getId())));
    }

    private RuleResponse mapToResponse(Rule rule, List<RuleAction> actions, ExecutionCounts counts) {
        ConditionNode conditions = parseConditions(rule);
        List<RuleActionResponse> actionResponses = actions.stream()
                .map(this::mapActionToResponse)
                .collect(Collectors.toList());
        long totalExec = counts.executions();
        long totalMatches = counts.matches();

        return RuleResponse.builder()
                .id(rule.getId())
                .projectId(rule.getProjectId())
                .name(rule.getName())
                .description(rule.getDescription())
                .enabled(rule.getEnabled())
                .priority(rule.getPriority())
                .eventTypePattern(rule.getEventTypePattern())
                .conditions(conditions)
                .actions(actionResponses)
                .totalExecutions(totalExec)
                .totalMatches(totalMatches)
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }

    private RuleActionResponse mapActionToResponse(RuleAction action) {
        String endpointUrl = null;
        if (action.getEndpointId() != null) {
            endpointUrl = endpointRepository.findById(action.getEndpointId())
                    .map(Endpoint::getUrl)
                    .orElse(null);
        }
        String transformationName = null;
        if (action.getTransformationId() != null) {
            transformationName = transformationRepository.findById(action.getTransformationId())
                    .map(Transformation::getName)
                    .orElse(null);
        }

        Object config;
        try {
            config = objectMapper.readValue(action.getConfig(), Object.class);
        } catch (Exception e) {
            config = Map.of();
        }

        return RuleActionResponse.builder()
                .id(action.getId())
                .type(action.getType())
                .endpointId(action.getEndpointId())
                .endpointUrl(endpointUrl)
                .transformationId(action.getTransformationId())
                .transformationName(transformationName)
                .config(config)
                .sortOrder(action.getSortOrder())
                .createdAt(action.getCreatedAt())
                .build();
    }
}
