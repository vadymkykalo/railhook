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
    private final EndpointRepository endpointRepository;
    private final ObjectMapper objectMapper;
    private final WorkflowEngine workflowEngine;

    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    private Workflow requireWorkflow(UUID projectId, UUID id) {
        return workflowRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Workflow not found"));
    }

    // Executors run with only the organization in scope, so this stops cross-project nodes.
    private void validateNodeProjects(Object definition, UUID projectId) {
        if (definition == null) {
            return;
        }
        JsonNode nodes = objectMapper.valueToTree(definition).path("nodes");
        for (JsonNode node : nodes) {
            String type = node.path("type").asText();
            if ("createEvent".equals(type)) {
                String target = node.path("data").path("projectId").asText("");
                if (!target.isBlank() && !projectId.equals(parseOrNull(target))) {
                    throw new NotFoundException("Project not found");
                }
            } else if ("delivery".equals(type)) {
                String target = node.path("data").path("endpointId").asText("");
                if (target.isBlank()) {
                    continue;
                }
                UUID endpointId = parseOrNull(target);
                if (endpointId == null || endpointRepository.findByIdAndProjectId(endpointId, projectId).isEmpty()) {
                    throw new NotFoundException("Endpoint not found");
                }
            }
        }
    }

    private static UUID parseOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Transactional
    public WorkflowResponse create(UUID projectId, WorkflowRequest request) {
        validateProjectOwnership(projectId);
        validateNodeProjects(request.getDefinition(), projectId);

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

    public WorkflowResponse get(UUID projectId, UUID id) {
        Workflow workflow = requireWorkflow(projectId, id);
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
    public WorkflowResponse update(UUID projectId, UUID id, WorkflowRequest request) {
        Workflow workflow = requireWorkflow(projectId, id);
        validateNodeProjects(request.getDefinition(), projectId);

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
    public void delete(UUID projectId, UUID id) {
        Workflow workflow = requireWorkflow(projectId, id);
        workflowRepository.delete(workflow);
        log.info("Deleted workflow '{}'", workflow.getName());
    }

    @Transactional
    public WorkflowResponse toggleEnabled(UUID projectId, UUID id, boolean enabled) {
        Workflow workflow = requireWorkflow(projectId, id);
        workflow.setEnabled(enabled);
        workflow = workflowRepository.save(workflow);
        log.info("Workflow '{}' {}", workflow.getName(), enabled ? "enabled" : "disabled");
        return mapToResponse(workflow);
    }

    public WorkflowExecutionResponse manualTrigger(UUID projectId, UUID workflowId, Object testPayload) {
        Workflow workflow = requireWorkflow(projectId, workflowId);

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

        execution = executionRepository.findById(execution.getId()).orElse(execution);
        return getExecution(projectId, workflowId, execution.getId());
    }

    public Page<WorkflowExecutionResponse> listExecutions(UUID projectId, UUID workflowId, int page, int size) {
        Workflow workflow = requireWorkflow(projectId, workflowId);

        return executionRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId, PageRequest.of(page, size))
                .map(exec -> {
                    WorkflowExecutionResponse resp = mapExecutionToResponse(exec);
                    List<WorkflowStepExecution> steps = stepExecutionRepository.findByExecutionIdOrderByCreatedAtAsc(exec.getId());
                    resp.setSteps(steps.stream().map(this::mapStepToResponse).collect(Collectors.toList()));
                    return resp;
                });
    }

    public WorkflowExecutionResponse getExecution(UUID projectId, UUID workflowId, UUID executionId) {
        requireWorkflow(projectId, workflowId);
        WorkflowExecution execution = executionRepository.findByIdAndWorkflowId(executionId, workflowId)
                .orElseThrow(() -> new NotFoundException("Execution not found"));

        WorkflowExecutionResponse response = mapExecutionToResponse(execution);
        List<WorkflowStepExecution> steps = stepExecutionRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
        response.setSteps(steps.stream().map(this::mapStepToResponse).collect(Collectors.toList()));
        return response;
    }

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
