package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.WorkflowExecutionResponse;
import com.webhook.platform.api.dto.WorkflowRequest;
import com.webhook.platform.api.dto.WorkflowResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.WorkflowService;
import com.webhook.platform.api.service.billing.RequireFeature;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflows")
@Tag(name = "Workflows", description = "Visual workflow automation")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @Operation(operationId = "createWorkflow", summary = "Create workflow",
            description = "Defines a workflow: the nodes it runs, the edges between them, and what "
                    + "triggers it. Created disabled until toggled on.")
    @ApiResponse(responseCode = "201", description = "Workflow created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireFeature("workflows")
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<WorkflowResponse> create(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody WorkflowRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workflowService.create(projectId, request));
    }

    @Operation(operationId = "getWorkflow", summary = "Get workflow",
            description = "Returns the definition, the trigger, and how its executions have gone.")
    @GetMapping("/{id}")
    public ResponseEntity<WorkflowResponse> get(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(workflowService.get(id));
    }

    @Operation(operationId = "listWorkflows", summary = "List workflows",
            description = "Every workflow in the project, newest first, each with its execution counts.")
    @GetMapping
    public ResponseEntity<List<WorkflowResponse>> list(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(workflowService.list(projectId));
    }

    @Operation(operationId = "updateWorkflow", summary = "Update workflow",
            description = "Replaces the definition and trigger. Executions already running are "
                    + "unaffected; the next one uses the new version.")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireFeature("workflows")
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{id}")
    public ResponseEntity<WorkflowResponse> update(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody WorkflowRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(workflowService.update(id, request));
    }

    @Operation(operationId = "deleteWorkflow", summary = "Delete workflow",
            description = "Removes the workflow and its execution history.")
    @ApiResponse(responseCode = "204", description = "Workflow deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "toggleWorkflow", summary = "Toggle workflow enabled/disabled",
            description = "A disabled workflow keeps its definition and stops being triggered.")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PatchMapping("/{id}/toggle")
    public ResponseEntity<WorkflowResponse> toggle(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @RequestBody Map<String, Boolean> body,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        boolean enabled = body.getOrDefault("enabled", true);
        return ResponseEntity.ok(workflowService.toggleEnabled(id, enabled));
    }

    @Operation(summary = "Manually trigger workflow with test payload",
            description = "Runs the workflow now against a payload you supply, without waiting for "
                    + "its trigger. The run is recorded like any other.")
    @ApiResponse(responseCode = "200", description = "Workflow executed")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireFeature("workflows")
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{id}/trigger")
    public ResponseEntity<WorkflowExecutionResponse> trigger(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @RequestBody(required = false) Map<String, Object> testPayload,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(workflowService.manualTrigger(id, testPayload));
    }

    // ── Executions ──────────────────────────────────────────────────────

    @Operation(summary = "List workflow executions",
            description = "Runs of this workflow, newest first, with the status each finished in.")
    @GetMapping("/{id}/executions")
    public ResponseEntity<Page<WorkflowExecutionResponse>> listExecutions(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(workflowService.listExecutions(id, page, size));
    }

    @Operation(summary = "Get execution details with step results",
            description = "One run, node by node: what each step was given, what it returned, and "
                    + "where the run stopped if it did.")
    @GetMapping("/{id}/executions/{executionId}")
    public ResponseEntity<WorkflowExecutionResponse> getExecution(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @PathVariable("executionId") UUID executionId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(workflowService.getExecution(executionId));
    }
}
