package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.CapturedRequestResponse;
import com.webhook.platform.api.dto.TestEndpointRequest;
import com.webhook.platform.api.dto.TestEndpointResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.TestEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/test-endpoints")
@RequiredArgsConstructor
@Tag(name = "Test Endpoints", description = "Webhook testing tool - temporary request bin endpoints")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class TestEndpointController {

    private final TestEndpointService testEndpointService;

    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    @Operation(operationId = "createTestEndpoint", summary = "Create a test endpoint", description = "Creates a temporary endpoint to capture webhook requests")
    public ResponseEntity<TestEndpointResponse> create(
            @PathVariable("projectId") UUID projectId,
            @RequestBody(required = false) TestEndpointRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        if (request == null) {
            request = new TestEndpointRequest();
        }
        return ResponseEntity.ok(testEndpointService.create(projectId, request));
    }

    @GetMapping
    @Operation(operationId = "listTestEndpoints", summary = "List test endpoints", description = "Lists all test endpoints for a project")
    public ResponseEntity<List<TestEndpointResponse>> list(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(testEndpointService.list(projectId));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getTestEndpoint", summary = "Get test endpoint", description = "Gets a specific test endpoint by ID")
    public ResponseEntity<TestEndpointResponse> get(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(testEndpointService.get(projectId, id));
    }

    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}")
    @Operation(operationId = "deleteTestEndpoint", summary = "Delete test endpoint", description = "Deletes a test endpoint and all captured requests")
    public ResponseEntity<Void> delete(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        testEndpointService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/requests")
    @Operation(summary = "Get captured requests", description = "Lists all requests captured by a test endpoint")
    public ResponseEntity<Page<CapturedRequestResponse>> getRequests(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @ParameterObject Pageable pageable,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(testEndpointService.getRequests(projectId, id, pageable));
    }

    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}/requests")
    @Operation(summary = "Clear captured requests", description = "Deletes all requests captured by a test endpoint")
    public ResponseEntity<Void> clearRequests(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        testEndpointService.clearRequests(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
