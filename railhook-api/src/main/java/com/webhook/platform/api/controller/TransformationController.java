package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.TransformationRequest;
import com.webhook.platform.api.dto.TransformationResponse;
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
                .body(transformationService.create(projectId, request));
    }

    @Operation(operationId = "getTransformation", summary = "Get transformation", description = "Returns transformation details")
    @GetMapping("/{id}")
    public ResponseEntity<TransformationResponse> get(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(transformationService.get(id));
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
        return ResponseEntity.ok(transformationService.update(id, request));
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
        transformationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
