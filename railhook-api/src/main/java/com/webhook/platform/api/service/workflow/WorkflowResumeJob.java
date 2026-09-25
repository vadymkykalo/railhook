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

/** Resumes delayed executions so no thread sleeps; the batch cap keeps a burst off the pool. */
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
                // Fail it, or it stays WAITING and is retried on every tick.
                log.error("Could not resume workflow execution {}: {}", execution.getId(), e.toString());
                failTerminally(execution, "Could not resume after delay: " + e.getMessage());
            }
        }
        log.debug("Resumed {} suspended workflow execution(s)", due.size());
    }

    private void resumeOne(WorkflowExecution execution) {
        var workflow = workflowRepository.findById(execution.getWorkflowId()).orElse(null);
        if (workflow == null) {
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
