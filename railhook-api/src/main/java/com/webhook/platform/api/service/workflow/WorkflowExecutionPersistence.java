package com.webhook.platform.api.service.workflow;

import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.domain.repository.WorkflowStepExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

// A separate bean so @Transactional is not bypassed by self-invocation inside WorkflowEngine.
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowExecutionPersistence {

    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowStepExecutionRepository stepRepository;
    private final ObjectMapper objectMapper;

    // Must leave RUNNING: the recovery job sweeps RUNNING and would fail a long delay as hung.
    @Transactional
    public void suspendExecution(UUID executionId, Instant resumeAt, JsonNode state, long workingMs) {
        executionRepository.findById(executionId).ifPresent(exec -> {
            exec.setStatus(ExecutionStatus.WAITING);
            exec.setResumeAt(resumeAt);
            exec.setWorkingMs(workingMs);
            try {
                exec.setResumeState(objectMapper.writeValueAsString(state));
            } catch (Exception e) {
                // Without a snapshot it can never resume, so fail it now.
                log.error("Could not serialise resume state for execution {}: {}", executionId, e.getMessage());
                exec.setStatus(ExecutionStatus.FAILED);
                exec.setErrorMessage("Could not persist resume state: " + e.getMessage());
                exec.setCompletedAt(Instant.now());
            }
            executionRepository.save(exec);
        });
    }

    @Transactional
    public void completeExecution(UUID executionId, ExecutionStatus status, String error, long startTime) {
        executionRepository.findById(executionId).ifPresent(exec -> {
            exec.setStatus(status);
            exec.setCompletedAt(Instant.now());
            exec.setDurationMs((int) (System.currentTimeMillis() - startTime));
            if (error != null) {
                exec.setErrorMessage(error.length() > 2000 ? error.substring(0, 2000) : error);
            }
            executionRepository.save(exec);
        });
    }

    @Transactional
    public WorkflowStepExecution saveStep(UUID executionId, String nodeId, String nodeType,
                                           JsonNode input, StepResult result, int durationMs) {
        return stepRepository.save(WorkflowStepExecution.builder()
                .executionId(executionId)
                .nodeId(nodeId)
                .nodeType(nodeType)
                .status(result.status())
                .inputData(jsonToString(input))
                .outputData(jsonToString(result.output()))
                .errorMessage(result.errorMessage())
                .durationMs(durationMs)
                .attemptCount(1)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build());
    }

    private String jsonToString(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
