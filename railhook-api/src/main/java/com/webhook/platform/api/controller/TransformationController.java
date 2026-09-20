package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.TransformationRequest;
import com.webhook.platform.api.dto.TransformationResponse;
import com.webhook.platform.api.dto.TransformationVersionDiffResponse;
import com.webhook.platform.api.dto.TransformationVersionResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.TransformationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/transformations")
@Tag(name = "Transformations", description = "Reusable payload transformation templates")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class TransformationController {

    private final TransformationService transformationService;

    @Operation(operationId = "createTransformation", summary = "Create transformation", description = "Creates a reusable payload transformation template")
    @ApiResponse(responseCode = "201", description = "Transformation created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<TransformationResponse> create(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody TransformationRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transformationService.create(projectId, request, auth.userId()));
    }

    @Operation(operationId = "getTransformation", summary = "Get transformation", description = "Returns transformation details")
    @GetMapping("/{id}")
    public ResponseEntity<TransformationResponse> get(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(transformationService.get(projectId, id));
    }

    @Operation(operationId = "listTransformations", summary = "List transformations", description = "Returns all transformations for the project")
    @GetMapping
    public ResponseEntity<List<TransformationResponse>> list(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(transformationService.list(projectId));
    }

    @Operation(operationId = "updateTransformation", summary = "Update transformation", description = "Updates transformation (auto-increments version when template changes)")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{id}")
    public ResponseEntity<TransformationResponse> update(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody TransformationRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(transformationService.update(projectId, id, request, auth.userId()));
    }

    @Operation(operationId = "deleteTransformation", summary = "Delete transformation", description = "Removes a transformation (subscriptions/destinations referencing it will have transformation_id set to NULL)")
    @ApiResponse(responseCode = "204", description = "Transformation deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        transformationService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "listTransformationVersions", summary = "List transformation versions",
            description = "Returns every published version of this transformation's template, newest first, with who published it and when. "
                    + "The templates themselves are omitted — fetch one version to read it.")
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<TransformationVersionResponse>> listVersions(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(transformationService.listVersions(projectId, id));
    }

    @Operation(operationId = "diffTransformationVersions", summary = "Compare two transformation versions",
            description = "Returns both templates whole and the list of places they differ, each named by its JSONPath")
    @GetMapping("/{id}/versions/diff")
    public ResponseEntity<TransformationVersionDiffResponse> diffVersions(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @RequestParam("left") int left,
            @RequestParam("right") int right,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(transformationService.diffVersions(projectId, id, left, right));
    }

    @Operation(operationId = "getTransformationVersion", summary = "Get one transformation version",
            description = "Returns one published version, including the template it published")
    @GetMapping("/{id}/versions/{version}")
    public ResponseEntity<TransformationVersionResponse> getVersion(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @PathVariable("version") int version,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(transformationService.getVersion(projectId, id, version));
    }

    @Operation(operationId = "restoreTransformationVersion", summary = "Restore a transformation version",
            description = "Publishes an earlier template again as a new version. The versions published after it are kept: "
                    + "a restore moves the transformation forward rather than rewinding its history.")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @PostMapping("/{id}/versions/{version}/restore")
    public ResponseEntity<TransformationResponse> restoreVersion(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @PathVariable("version") int version,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(transformationService.restoreVersion(projectId, id, version, auth.userId()));
    }
}
