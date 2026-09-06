package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.ApiKeyRequest;
import com.webhook.platform.api.dto.ApiKeyResponse;
import com.webhook.platform.api.dto.ApiKeyRotateRequest;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-keys")
@Tag(name = "API Keys", description = "API key management for event ingestion")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Operation(summary = "Create API key", description = "Generates a new API key for event ingestion. The key is shown only once.")
    @ApiResponse(responseCode = "201", description = "API key created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<ApiKeyResponse> createApiKey(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody ApiKeyRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        ApiKeyResponse response = apiKeyService.createApiKey(projectId, request);
        log.info("Created API key {} for project {}", response.getId(), projectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List API keys", description = "Returns paginated API keys for the project (keys are masked)")
    @GetMapping
    public ResponseEntity<Page<ApiKeyResponse>> listApiKeys(
            @PathVariable("projectId") UUID projectId,
            @PageableDefault(size = 20) @ParameterObject Pageable pageable,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        Page<ApiKeyResponse> response = apiKeyService.listApiKeys(projectId, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Rotate API key",
            description = "Issues a replacement key and puts the old one into a grace window, during which "
                    + "both authenticate. The replacement is shown only once. Default window is 24 hours; "
                    + "pass gracePeriodHours=0 to cut the old key off immediately after a suspected leak.")
    @ApiResponse(responseCode = "201", description = "Replacement key created; the old key is retiring")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @PostMapping("/{apiKeyId}/rotate")
    public ResponseEntity<ApiKeyResponse> rotateApiKey(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("apiKeyId") UUID apiKeyId,
            @Valid @RequestBody(required = false) ApiKeyRotateRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        ApiKeyResponse response = apiKeyService.rotateApiKey(projectId, apiKeyId, request);
        log.info("Rotated API key {} for project {} into {}", apiKeyId, projectId, response.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Revoke API key", description = "Permanently revokes an API key")
    @ApiResponse(responseCode = "204", description = "API key revoked")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{apiKeyId}")
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("apiKeyId") UUID apiKeyId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        apiKeyService.revokeApiKey(projectId, apiKeyId);
        log.info("Revoked API key {} for project {}", apiKeyId, projectId);
        return ResponseEntity.noContent().build();
    }
}
