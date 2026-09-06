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

/**
 * Triggers matching workflows when a webhook event is ingested.
 *
 * <p>Primary path: {@link #triggerWorkflowsSync} called by {@code WorkflowTriggerOutboxService}
 * after the outbox poller claims a row. This guarantees at-least-once execution
 * even if the API crashes between event commit and workflow trigger.</p>
 *
 * Reliability features:
 * - Durable outbox: trigger intent persisted in same TX as event
 * - Bounded thread pool (workflowTaskExecutor): configurable concurrent workflows
 * - Recursion depth guard: configurable max chained hops
 * - Idempotency: unique index (workflow_id, trigger_event_id) prevents duplicate runs
 * - ThreadLocal depth tracking for cross-service recursion detection
 */
@Service
@Slf4j
public class WorkflowTriggerService {

    /**
     * ThreadLocal tracking current workflow execution depth.
     * Set by WorkflowEngine before executing nodes, read by EventIngestService
     * to pass depth into the next triggerWorkflows call.
     * Reset after workflow execution completes.
     */
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

    /** Read current workflow depth from calling thread (used by EventIngestService). */
    public static int getCurrentDepth() {
        return CURRENT_DEPTH.get();
    }

    /** Set workflow depth on current thread (used by WorkflowEngine). */
    public static void setCurrentDepth(int depth) {
        CURRENT_DEPTH.set(depth);
    }

    /** Clear depth ThreadLocal (called after workflow execution). */
    public static void clearCurrentDepth() {
        CURRENT_DEPTH.remove();
    }

    /**
     * Synchronous workflow trigger — called by {@code WorkflowTriggerOutboxService}.
     * Runs on the outbox poller thread (bounded by workflowTaskExecutor).
     * Throws on failure so the outbox can retry.
     */
    public void triggerWorkflowsSync(UUID projectId, UUID eventId, String eventType, String eventPayload, int depth) {
        doTriggerWorkflows(projectId, eventId, eventType, eventPayload, depth);
    }

    /**
     * @deprecated Use durable outbox path via {@link #triggerWorkflowsSync} instead.
     * Kept for backward compatibility during transition.
     */
    @Deprecated
    @Async("workflowTaskExecutor")
    public void triggerWorkflows(UUID projectId, UUID eventId, String eventType, String eventPayload, int depth) {
        doTriggerWorkflows(projectId, eventId, eventType, eventPayload, depth);
    }

    private void doTriggerWorkflows(UUID projectId, UUID eventId, String eventType, String eventPayload, int depth) {
        // ── Recursion guard ──────────────────────────────────────
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
                // The only caller is the outbox poller, which runs under TenantContext.SYSTEM:
                // Hibernate adds no predicate and, more importantly, stamps nothing on insert.
                // Everything below belongs to the workflow's organization — the execution row,
                // and the endpoints and deliveries its nodes go on to touch — so enter that
                // organization's scope here, outside any transaction.
                TenantContext.runAs(workflow.getOrganizationId(),
                        () -> triggerOne(workflow, eventId, eventType, eventPayload, eventJson, depth));
            } catch (Exception e) {
                log.error("Failed to trigger workflow '{}' for event {}: {}",
                        workflow.getName(), eventId, e.getMessage(), e);
            }
        }
    }

    /** Runs one matched workflow. Called inside that workflow's organization scope. */
    private void triggerOne(Workflow workflow, UUID eventId, String eventType,
                            String eventPayload, JsonNode eventJson, int depth) {
        // ── Idempotency guard ────────────────────────────
        if (eventId != null && executionRepository.existsByWorkflowIdAndTriggerEventId(
                workflow.getId(), eventId)) {
            log.debug("Skipping duplicate: workflow {} already triggered for event {}",
                    workflow.getId(), eventId);
            return;
        }

        // ── Create execution with depth tracking ─────────
        WorkflowExecution execution;
        try {
            execution = executionRepository.save(WorkflowExecution.builder()
                    .workflowId(workflow.getId())
                    .triggerEventId(eventId)
                    .triggerData(eventPayload)
                    .depth(depth)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // The unique index on (workflow_id, trigger_event_id) is the only violation this
            // path expects. Reporting every other one as a duplicate is what kept the missing
            // organization_id stamp invisible: confirm the duplicate, or let the failure out.
            if (eventId == null || !executionRepository.existsByWorkflowIdAndTriggerEventId(
                    workflow.getId(), eventId)) {
                throw e;
            }
            log.debug("Concurrent duplicate prevented: workflow {} event {}", workflow.getId(), eventId);
            return;
        }

        log.info("Triggering workflow '{}' (id={}) for event {} (type={}) depth={}",
                workflow.getName(), workflow.getId(), eventId, eventType, depth);

        // ── Set depth ThreadLocal before engine execution ─
        try {
            setCurrentDepth(depth);
            workflowEngine.execute(execution.getId(), workflow.getDefinition(), eventJson);
        } finally {
            clearCurrentDepth();
        }
    }

    /**
     * Check if the workflow's trigger config matches the event type.
     */
    private boolean matchesTrigger(Workflow workflow, String eventType) {
        try {
            JsonNode config = objectMapper.readTree(workflow.getTriggerConfig());
            if (config.has("eventTypePattern")) {
                String pattern = config.get("eventTypePattern").asText();
                if (pattern != null && !pattern.isBlank()) {
                    return EventTypeMatcher.matches(pattern, eventType);
                }
            }
            // No pattern = match all events
            return true;
        } catch (Exception e) {
            log.warn("Failed to parse trigger config for workflow {}: {}", workflow.getId(), e.getMessage());
            return false;
        }
    }
}
