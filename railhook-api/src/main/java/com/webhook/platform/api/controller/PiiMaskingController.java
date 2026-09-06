package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.PiiMaskingRuleRequest;
import com.webhook.platform.api.dto.PiiMaskingRuleResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.PiiMaskingService;
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
@RequestMapping("/api/v1/projects/{projectId}/pii-rules")
@Tag(name = "PII Masking", description = "PII masking rules for payload sanitization")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class PiiMaskingController {

    private final PiiMaskingService piiMaskingService;

    @Operation(operationId = "listPiiRules", summary = "List PII masking rules", description = "Returns all masking rules for the project")
    @GetMapping
    public ResponseEntity<List<PiiMaskingRuleResponse>> listRules(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(piiMaskingService.listRules(projectId));
    }

    @Operation(operationId = "createPiiRule", summary = "Create PII masking rule", description = "Creates a new masking rule for the project")
    @ApiResponse(responseCode = "201", description = "Rule created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<PiiMaskingRuleResponse> createRule(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody PiiMaskingRuleRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        PiiMaskingRuleResponse response = piiMaskingService.createRule(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(operationId = "updatePiiRule", summary = "Update PII masking rule", description = "Updates an existing masking rule")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{ruleId}")
    public ResponseEntity<PiiMaskingRuleResponse> updateRule(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("ruleId") UUID ruleId,
            @Valid @RequestBody PiiMaskingRuleRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(piiMaskingService.updateRule(projectId, ruleId, request));
    }

    @Operation(operationId = "deletePiiRule", summary = "Delete PII masking rule", description = "Deletes a masking rule")
    @ApiResponse(responseCode = "204", description = "Rule deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("ruleId") UUID ruleId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        piiMaskingService.deleteRule(projectId, ruleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Seed default rules", description = "Creates default built-in PII masking rules (email, phone, card)")
    @ApiResponse(responseCode = "200", description = "Default rules seeded")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/seed-defaults")
    public ResponseEntity<List<PiiMaskingRuleResponse>> seedDefaults(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        piiMaskingService.seedDefaultRules(projectId);
        return ResponseEntity.ok(piiMaskingService.listRules(projectId));
    }

    @Operation(summary = "Preview sanitized payload", description = "Applies current PII rules to a sample payload and returns the result")
    @PostMapping("/preview")
    public ResponseEntity<String> previewSanitization(
            @PathVariable("projectId") UUID projectId,
            @RequestBody String payload,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        String sanitized = piiMaskingService.sanitizePayload(projectId, payload);
        return ResponseEntity.ok(sanitized);
    }
}
