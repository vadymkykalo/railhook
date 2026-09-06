package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.api.dto.EndpointResponse;
import com.webhook.platform.api.dto.EndpointTestResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.EndpointService;
import com.webhook.platform.api.service.billing.QuotaType;
import com.webhook.platform.api.service.billing.RequireFeature;
import com.webhook.platform.api.service.billing.RequireQuota;
import com.webhook.platform.api.service.EndpointVerificationService;
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
@RequestMapping("/api/v1/projects/{projectId}/endpoints")
@Tag(name = "Endpoints", description = "Webhook endpoint configuration")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class EndpointController {

    private final EndpointService endpointService;
    private final EndpointVerificationService verificationService;

    public EndpointController(EndpointService endpointService, EndpointVerificationService verificationService) {
        this.endpointService = endpointService;
        this.verificationService = verificationService;
    }

    @Operation(summary = "Create endpoint", description = "Creates a new webhook endpoint for the project")
    @ApiResponse(responseCode = "201", description = "Endpoint created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireQuota(QuotaType.ENDPOINTS_PER_PROJECT)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<EndpointResponse> createEndpoint(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody EndpointRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        try {
            EndpointResponse response = endpointService.createEndpoint(projectId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Failed to create endpoint for project {}: {}", projectId, e.getMessage(), e);
            throw e;
        }
    }

    @Operation(summary = "Get endpoint", description = "Returns endpoint details by ID")
    @GetMapping("/{id}")
    public ResponseEntity<EndpointResponse> getEndpoint(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        EndpointResponse response = endpointService.getEndpoint(id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "List endpoints", description = "Returns paginated endpoints for the project")
    @GetMapping
    public ResponseEntity<Page<EndpointResponse>> listEndpoints(
            @PathVariable("projectId") UUID projectId,
            @PageableDefault(size = 20) @ParameterObject Pageable pageable,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        Page<EndpointResponse> response = endpointService.listEndpoints(projectId, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Update endpoint", description = "Updates endpoint configuration")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{id}")
    public ResponseEntity<EndpointResponse> updateEndpoint(
            @PathVariable("id") UUID id,
            @Valid @RequestBody EndpointRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        EndpointResponse response = endpointService.updateEndpoint(id, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Delete endpoint", description = "Deletes an endpoint")
    @ApiResponse(responseCode = "204", description = "Endpoint deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEndpoint(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        endpointService.deleteEndpoint(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rotate secret", description = "Generates a new webhook signing secret")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{id}/rotate-secret")
    public ResponseEntity<EndpointResponse> rotateSecret(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        EndpointResponse response = endpointService.rotateSecret(id);
        log.info("Rotated secret for endpoint {}", id);
        return ResponseEntity.ok(response);
    }

    // Fires a real outbound request from the platform, signed with the endpoint's own
    // secret — the same capability rotate-secret above is guarded for. Tenancy was already
    // safe (endpointService.testEndpoint validates project ownership), but scope and role
    // were not checked at all, so a Viewer or a READ_ONLY key could drive it.
    @Operation(summary = "Test endpoint", description = "Sends a test webhook to verify endpoint connectivity")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{id}/test")
    public ResponseEntity<EndpointTestResponse> testEndpoint(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        EndpointTestResponse response = endpointService.testEndpoint(id);
        log.info("Tested endpoint {}: success={}, latency={}ms", id, response.isSuccess(), response.getLatencyMs());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Configure mTLS", description = "Configures mutual TLS for the endpoint")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireFeature("mTLS")
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{id}/mtls")
    public ResponseEntity<EndpointResponse> configureMtls(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody com.webhook.platform.api.dto.MtlsConfigRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        EndpointResponse response = endpointService.configureMtls(projectId, id, request);
        log.info("Configured mTLS for endpoint {}", id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Disable mTLS", description = "Disables mutual TLS for the endpoint")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireFeature("mTLS")
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}/mtls")
    public ResponseEntity<EndpointResponse> disableMtls(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        EndpointResponse response = endpointService.disableMtls(projectId, id);
        log.info("Disabled mTLS for endpoint {}", id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Verify endpoint", description = "Sends a verification challenge to the endpoint")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{id}/verify")
    public ResponseEntity<VerificationResponse> verifyEndpoint(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        // Mutates verification_status, exactly like skip-verification below.
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        var result = verificationService.verify(id);
        log.info("Verification attempt for endpoint {}: success={}", id, result.success());
        
        return ResponseEntity.ok(new VerificationResponse(
                result.success(),
                result.message(),
                result.endpoint().getVerificationStatus().name()
        ));
    }

    @Operation(summary = "Skip verification", description = "Skips verification for trusted endpoints (admin only)")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{id}/skip-verification")
    public ResponseEntity<EndpointResponse> skipVerification(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            @RequestBody(required = false) SkipVerificationRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        
        String reason = request != null ? request.reason() : "Skipped by administrator";
        var endpoint = verificationService.skipVerification(id, reason);
        log.info("Skipped verification for endpoint {}: {}", id, reason);
        
        return ResponseEntity.ok(endpointService.getEndpoint(id));
    }

    public record VerificationResponse(boolean success, String message, String status) {}
    public record SkipVerificationRequest(String reason) {}
}
