package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Workflow;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.EventTypeMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Runs from the trigger outbox; a unique (workflow_id, trigger_event_id) index makes redelivery harmless. */
@Service
@Slf4j
public class WorkflowTriggerService {

    // Lets an event created by a workflow carry the depth of the chain that created it.
    private static final ThreadLocal<Integer> CURRENT_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowEngine workflowEngine;
    private final ObjectMapper objectMapper;
    private final int maxRecursionDepth;

    public WorkflowTriggerService(
            WorkflowRepository workflowRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowEngine workflowEngine,
            ObjectMapper objectMapper,
            @Value("${workflow.execution.max-recursion-depth:3}") int maxRecursionDepth) {
        this.workflowRepository = workflowRepository;
        this.executionRepository = executionRepository;
        this.workflowEngine = workflowEngine;
        this.objectMapper = objectMapper;
        this.maxRecursionDepth = maxRecursionDepth;
    }

    public static int getCurrentDepth() {
        return CURRENT_DEPTH.get();
    }

    public static void setCurrentDepth(int depth) {
        CURRENT_DEPTH.set(depth);
    }

    public static void clearCurrentDepth() {
        CURRENT_DEPTH.remove();
    }

    /** Throws on failure so the outbox can retry. */
    public void triggerWorkflowsSync(UUID projectId, UUID eventId, String eventType, String eventPayload, int depth) {
        doTriggerWorkflows(projectId, eventId, eventType, eventPayload, depth);
    }

    /** @deprecated use {@link #triggerWorkflowsSync} through the outbox. */
    @Deprecated
    @Async("workflowTaskExecutor")
    public void triggerWorkflows(UUID projectId, UUID eventId, String eventType, String eventPayload, int depth) {
        doTriggerWorkflows(projectId, eventId, eventType, eventPayload, depth);
    }

    private void doTriggerWorkflows(UUID projectId, UUID eventId, String eventType, String eventPayload, int depth) {
        if (depth > maxRecursionDepth) {
            log.warn("Workflow recursion depth {} exceeds max {} for event {} — skipping",
                    depth, maxRecursionDepth, eventId);
            return;
        }

        List<Workflow> workflows = workflowRepository.findEnabledWebhookWorkflows(projectId);
        if (workflows.isEmpty()) return;

        JsonNode eventJson;
        try {
            eventJson = objectMapper.readTree(eventPayload);
        } catch (Exception e) {
            log.error("Failed to parse event payload for workflow trigger: eventId={}", eventId, e);
            return;
        }

        for (Workflow workflow : workflows) {
            if (!matchesTrigger(workflow, eventType)) continue;
            try {
                // Under SYSTEM, Hibernate stamps no tenant on insert.
                TenantContext.runAs(workflow.getOrganizationId(),
                        () -> triggerOne(workflow, eventId, eventType, eventPayload, eventJson, depth));
            } catch (Exception e) {
                log.error("Failed to trigger workflow '{}' for event {}: {}",
                        workflow.getName(), eventId, e.getMessage(), e);
            }
        }
    }

    private void triggerOne(Workflow workflow, UUID eventId, String eventType,
                            String eventPayload, JsonNode eventJson, int depth) {
        if (eventId != null && executionRepository.existsByWorkflowIdAndTriggerEventId(
                workflow.getId(), eventId)) {
            log.debug("Skipping duplicate: workflow {} already triggered for event {}",
                    workflow.getId(), eventId);
            return;
        }

        WorkflowExecution execution;
        try {
            execution = executionRepository.save(WorkflowExecution.builder()
                    .workflowId(workflow.getId())
                    .triggerEventId(eventId)
                    .triggerData(eventPayload)
                    .depth(depth)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Treating every violation as a duplicate once hid a missing organization_id.
            if (eventId == null || !executionRepository.existsByWorkflowIdAndTriggerEventId(
                    workflow.getId(), eventId)) {
                throw e;
            }
            log.debug("Concurrent duplicate prevented: workflow {} event {}", workflow.getId(), eventId);
            return;
        }

        log.info("Triggering workflow '{}' (id={}) for event {} (type={}) depth={}",
                workflow.getName(), workflow.getId(), eventId, eventType, depth);

        try {
            setCurrentDepth(depth);
            workflowEngine.execute(execution.getId(), workflow.getDefinition(), eventJson);
        } finally {
            clearCurrentDepth();
        }
    }

    private boolean matchesTrigger(Workflow workflow, String eventType) {
        try {
            JsonNode config = objectMapper.readTree(workflow.getTriggerConfig());
            if (config.has("eventTypePattern")) {
                String pattern = config.get("eventTypePattern").asText();
                if (pattern != null && !pattern.isBlank()) {
                    return EventTypeMatcher.matches(pattern, eventType);
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to parse trigger config for workflow {}: {}", workflow.getId(), e.getMessage());
            return false;
        }
    }
}
