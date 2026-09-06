package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.Workflow;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution;
import com.webhook.platform.api.domain.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.api.dto.WorkflowExecutionResponse;
import com.webhook.platform.api.dto.WorkflowExecutionResponse.StepExecutionResponse;
import com.webhook.platform.api.dto.WorkflowRequest;
import com.webhook.platform.api.dto.WorkflowResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.service.workflow.WorkflowEngine;
import com.webhook.platform.api.service.workflow.WorkflowTriggerService;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowStepExecutionRepository stepExecutionRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final WorkflowEngine workflowEngine;

    /**
     * Turns "no such project here" into a 404. {@code Project} carries {@code @TenantId}, so this
     * lookup only sees projects inside the caller's organization: a foreign project id is
     * indistinguishable from a missing one, which is intended.
     */
    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    @Transactional
    public WorkflowResponse create(UUID projectId, WorkflowRequest request) {
        validateProjectOwnership(projectId);

        if (workflowRepository.existsByProjectIdAndName(projectId, request.getName())) {
            throw new ConflictException("Workflow with this name already exists");
        }

        Workflow workflow = Workflow.builder()
                .projectId(projectId)
                .name(request.getName())
                .description(request.getDescription())
                .enabled(request.getEnabled() != null ? request.getEnabled() : false)
                .definition(serializeJson(request.getDefinition()))
                .triggerType(request.getTriggerType() != null ? request.getTriggerType() : Workflow.TriggerType.WEBHOOK_EVENT)
                .triggerConfig(serializeJson(request.getTriggerConfig()))
                .build();

        workflow = workflowRepository.save(workflow);
        log.info("Created workflow '{}' for project {}", workflow.getName(), projectId);
        return mapToResponse(workflow);
    }

    public WorkflowResponse get(UUID id) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found"));
        validateProjectOwnership(workflow.getProjectId());
        return mapToResponse(workflow);
    }

    public List<WorkflowResponse> list(UUID projectId) {
        validateProjectOwnership(projectId);
        List<Workflow> workflows = workflowRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Map<UUID, ExecutionCounts> counts = executionCountsFor(
                workflows.stream().map(Workflow::getId).collect(Collectors.toSet()));

        return workflows.stream()
                .map(w -> mapToResponse(w, counts.getOrDefault(w.getId(), ExecutionCounts.NONE)))
                .collect(Collectors.toList());
    }

    /** What a workflow's execution history amounts to: the three states a listing shows. */
    private record ExecutionCounts(long succeeded, long failed, long running) {

        static final ExecutionCounts NONE = new ExecutionCounts(0, 0, 0);

        long total() {
            return succeeded + failed + running;
        }
    }

    private Map<UUID, ExecutionCounts> executionCountsFor(Set<UUID> workflowIds) {
        Map<UUID, ExecutionCounts> counts = new HashMap<>();
        for (Object[] row : executionRepository.countByWorkflowIdsGroupedByStatus(workflowIds)) {
            UUID workflowId = (UUID) row[0];
            ExecutionStatus status = (ExecutionStatus) row[1];
            long count = (Long) row[2];
            ExecutionCounts current = counts.getOrDefault(workflowId, ExecutionCounts.NONE);
            counts.put(workflowId, switch (status) {
                case COMPLETED -> new ExecutionCounts(count, current.failed(), current.running());
                case FAILED -> new ExecutionCounts(current.succeeded(), count, current.running());
                case RUNNING -> new ExecutionCounts(current.succeeded(), current.failed(), count);
                default -> current;
            });
        }
        return counts;
    }

    @Transactional
    public WorkflowResponse update(UUID id, WorkflowRequest request) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found"));
        validateProjectOwnership(workflow.getProjectId());

        // Check name uniqueness if changed
        if (!workflow.getName().equals(request.getName()) &&
                workflowRepository.existsByProjectIdAndName(workflow.getProjectId(), request.getName())) {
            throw new ConflictException("Workflow with this name already exists");
        }

        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        if (request.getEnabled() != null) {
            workflow.setEnabled(request.getEnabled());
        }
        if (request.getDefinition() != null) {
            workflow.setDefinition(serializeJson(request.getDefinition()));
            workflow.setVersion(workflow.getVersion() + 1);
        }
        if (request.getTriggerType() != null) {
            workflow.setTriggerType(request.getTriggerType());
        }
        if (request.getTriggerConfig() != null) {
            workflow.setTriggerConfig(serializeJson(request.getTriggerConfig()));
        }

        workflow = workflowRepository.save(workflow);
        log.info("Updated workflow '{}' (v{})", workflow.getName(), workflow.getVersion());
        return mapToResponse(workflow);
    }

    @Transactional
    public void delete(UUID id) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found"));
        validateProjectOwnership(workflow.getProjectId());
        workflowRepository.delete(workflow);
        log.info("Deleted workflow '{}'", workflow.getName());
    }

    @Transactional
    public WorkflowResponse toggleEnabled(UUID id, boolean enabled) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found"));
        validateProjectOwnership(workflow.getProjectId());
        workflow.setEnabled(enabled);
        workflow = workflowRepository.save(workflow);
        log.info("Workflow '{}' {}", workflow.getName(), enabled ? "enabled" : "disabled");
        return mapToResponse(workflow);
    }

    // ── Manual trigger ───────────────────────────────────────────────────

    public WorkflowExecutionResponse manualTrigger(UUID workflowId, Object testPayload) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("Workflow not found"));
        validateProjectOwnership(workflow.getProjectId());

        String payloadStr;
        JsonNode payloadJson;
        try {
            payloadStr = testPayload != null ? objectMapper.writeValueAsString(testPayload) : "{}";
            payloadJson = objectMapper.readTree(payloadStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid test payload: " + e.getMessage());
        }

        WorkflowExecution execution = executionRepository.save(WorkflowExecution.builder()
                .workflowId(workflowId)
                .triggerData(payloadStr)
                .build());

        log.info("Manual trigger workflow '{}' (id={}), execution={}", workflow.getName(), workflowId, execution.getId());

        try {
            WorkflowTriggerService.setCurrentDepth(0);
            workflowEngine.execute(execution.getId(), workflow.getDefinition(), payloadJson);
        } finally {
            WorkflowTriggerService.clearCurrentDepth();
        }

        // Re-fetch to get updated status
        execution = executionRepository.findById(execution.getId()).orElse(execution);
        return getExecution(execution.getId());
    }

    // ── Executions ──────────────────────────────────────────────────────

    public Page<WorkflowExecutionResponse> listExecutions(UUID workflowId, int page, int size) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("Workflow not found"));
        validateProjectOwnership(workflow.getProjectId());

        return executionRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId, PageRequest.of(page, size))
                .map(exec -> {
                    WorkflowExecutionResponse resp = mapExecutionToResponse(exec);
                    List<WorkflowStepExecution> steps = stepExecutionRepository.findByExecutionIdOrderByCreatedAtAsc(exec.getId());
                    resp.setSteps(steps.stream().map(this::mapStepToResponse).collect(Collectors.toList()));
                    return resp;
                });
    }

    public WorkflowExecutionResponse getExecution(UUID executionId) {
        WorkflowExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new NotFoundException("Execution not found"));

        Workflow workflow = workflowRepository.findById(execution.getWorkflowId())
                .orElseThrow(() -> new NotFoundException("Workflow not found"));
        validateProjectOwnership(workflow.getProjectId());

        WorkflowExecutionResponse response = mapExecutionToResponse(execution);
        List<WorkflowStepExecution> steps = stepExecutionRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
        response.setSteps(steps.stream().map(this::mapStepToResponse).collect(Collectors.toList()));
        return response;
    }

    // ── Mapping ─────────────────────────────────────────────────────────

    private WorkflowResponse mapToResponse(Workflow w) {
        return mapToResponse(w, new ExecutionCounts(
                executionRepository.countByWorkflowIdAndStatus(w.getId(), ExecutionStatus.COMPLETED),
                executionRepository.countByWorkflowIdAndStatus(w.getId(), ExecutionStatus.FAILED),
                executionRepository.countByWorkflowIdAndStatus(w.getId(), ExecutionStatus.RUNNING)));
    }

    private WorkflowResponse mapToResponse(Workflow w, ExecutionCounts counts) {
        return WorkflowResponse.builder()
                .id(w.getId())
                .projectId(w.getProjectId())
                .name(w.getName())
                .description(w.getDescription())
                .enabled(w.getEnabled())
                .definition(parseJson(w.getDefinition()))
                .triggerType(w.getTriggerType())
                .triggerConfig(parseJson(w.getTriggerConfig()))
                .version(w.getVersion())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .totalExecutions(counts.total())
                .successfulExecutions(counts.succeeded())
                .failedExecutions(counts.failed())
                .build();
    }

    private WorkflowExecutionResponse mapExecutionToResponse(WorkflowExecution e) {
        return WorkflowExecutionResponse.builder()
                .id(e.getId())
                .workflowId(e.getWorkflowId())
                .triggerEventId(e.getTriggerEventId())
                .status(e.getStatus())
                .triggerData(parseJson(e.getTriggerData()))
                .startedAt(e.getStartedAt())
                .completedAt(e.getCompletedAt())
                .errorMessage(e.getErrorMessage())
                .durationMs(e.getDurationMs())
                .build();
    }

    private StepExecutionResponse mapStepToResponse(WorkflowStepExecution s) {
        return StepExecutionResponse.builder()
                .id(s.getId())
                .nodeId(s.getNodeId())
                .nodeType(s.getNodeType())
                .status(s.getStatus())
                .inputData(parseJson(s.getInputData()))
                .outputData(parseJson(s.getOutputData()))
                .errorMessage(s.getErrorMessage())
                .attemptCount(s.getAttemptCount())
                .durationMs(s.getDurationMs())
                .startedAt(s.getStartedAt())
                .completedAt(s.getCompletedAt())
                .build();
    }

    // ── JSON helpers ────────────────────────────────────────────────────

    private String serializeJson(Object obj) {
        if (obj == null) return "{}";
        if (obj instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
