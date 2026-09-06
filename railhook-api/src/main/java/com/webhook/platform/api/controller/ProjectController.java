package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.ProjectResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.ProjectService;
import com.webhook.platform.api.service.billing.QuotaType;
import com.webhook.platform.api.service.billing.RequireQuota;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Project management")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Operation(summary = "Create project", description = "Creates a new project in the organization")
    @ApiResponse(responseCode = "201", description = "Project created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireQuota(QuotaType.PROJECTS)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<ProjectResponse> createProject(
            @Valid @RequestBody ProjectRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        ProjectResponse response = projectService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get project", description = "Returns project details by ID")
    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getProject(
            @PathVariable("projectId") UUID id,
            AuthContext auth) {
        ProjectResponse response = projectService.getProject(id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "List projects", description = "Returns all projects in the organization")
    @GetMapping
    public ResponseEntity<List<ProjectResponse>> listProjects(AuthContext auth) {
        List<ProjectResponse> response = projectService.listProjects();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Update project", description = "Updates project details")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> updateProject(
            @PathVariable("projectId") UUID id,
            @Valid @RequestBody ProjectRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        ProjectResponse response = projectService.updateProject(id, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Delete project", description = "Deletes a project and all associated resources")
    @ApiResponse(responseCode = "204", description = "Project deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable("projectId") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }
}
