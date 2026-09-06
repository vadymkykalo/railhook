package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Continues executions that suspended at a delay node.
 *
 * <p>The other half of not sleeping. {@code DelayNodeExecutor} returns a due time instead of
 * blocking, {@code WorkflowEngine} writes down where the execution got to and releases the
 * thread, and this picks it up once the delay has expired.
 *
 * <p>Resolution is the poll interval, so a delay is "at least N seconds", never exactly N —
 * which is what a delay in a workflow means anyway. The batch cap matters more than the
 * interval: a burst of executions all becoming due at the same second must not hand the
 * workflow pool more work at once than it could take, so they spill to the next tick in
 * {@code resumeAt} order rather than being rejected.
 *
 * <p>Runs {@code @SystemTenant} because suspended executions belong to every organization, and
 * re-enters each execution's own tenant before touching it — the engine writes step rows, and
 * those are tenant-scoped.
 */
@Service
@Slf4j
public class WorkflowResumeJob {

    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public WorkflowResumeJob(
            WorkflowExecutionRepository executionRepository,
            WorkflowRepository workflowRepository,
            WorkflowEngine engine,
            ObjectMapper objectMapper,
            @Value("${workflow.execution.resume-batch-size:50}") int batchSize) {
        this.executionRepository = executionRepository;
        this.workflowRepository = workflowRepository;
        this.engine = engine;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @SystemTenant("suspended executions belong to every organization; each is resumed inside its own")
    @Scheduled(fixedDelayString = "${workflow.execution.resume-interval-ms:5000}")
    @SchedulerLock(name = "resumeWorkflowExecutions", lockAtMostFor = "5m", lockAtLeastFor = "1s")
    public void resumeDueExecutions() {
        List<WorkflowExecution> due;
        try {
            due = executionRepository.findDueForResume(Instant.now(), PageRequest.of(0, batchSize));
        } catch (Exception e) {
            log.error("Could not read due workflow executions: {}", e.getMessage(), e);
            return;
        }
        if (due.isEmpty()) {
            return;
        }

        for (WorkflowExecution execution : due) {
            try {
                resumeOne(execution);
            } catch (Exception e) {
                // One execution that cannot be resumed must not strand the rest of the batch —
                // they belong to other organizations. Fail this one so it leaves WAITING rather
                // than being retried forever on every tick.
                log.error("Could not resume workflow execution {}: {}", execution.getId(), e.toString());
                failTerminally(execution, "Could not resume after delay: " + e.getMessage());
            }
        }
        log.debug("Resumed {} suspended workflow execution(s)", due.size());
    }

    private void resumeOne(WorkflowExecution execution) {
        var workflow = workflowRepository.findById(execution.getWorkflowId()).orElse(null);
        if (workflow == null) {
            // The workflow was deleted while its execution slept. There is nothing to continue,
            // and leaving the row WAITING would poll it forever.
            failTerminally(execution, "Workflow was deleted while this execution was suspended");
            return;
        }

        JsonNode state;
        JsonNode triggerData;
        try {
            state = execution.getResumeState() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(execution.getResumeState());
            triggerData = execution.getTriggerData() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(execution.getTriggerData());
        } catch (Exception e) {
            failTerminally(execution, "Resume state could not be read: " + e.getMessage());
            return;
        }

        long workingMs = execution.getWorkingMs() == null ? 0L : execution.getWorkingMs();

        // Back into the execution's own organization: the engine writes step rows, which are
        // tenant-scoped, and this job runs with the tenant filter off.
        TenantContext.runAs(execution.getOrganizationId(), () ->
                engine.resume(execution.getId(), workflow.getDefinition(), triggerData, state, workingMs));
    }

    private void failTerminally(WorkflowExecution execution, String reason) {
        try {
            TenantContext.runAs(execution.getOrganizationId(), () -> {
                execution.setStatus(ExecutionStatus.FAILED);
                execution.setErrorMessage(reason);
                execution.setCompletedAt(Instant.now());
                execution.setResumeAt(null);
                executionRepository.save(execution);
            });
        } catch (Exception e) {
            log.error("Could not mark execution {} failed: {}", execution.getId(), e.getMessage());
        }
    }
}
