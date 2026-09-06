package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.EncryptionRotationResponse;
import com.webhook.platform.api.dto.EncryptionStatusResponse;
import com.webhook.platform.api.service.EncryptionKeyRotationService;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Cluster-operator endpoints — encryption key rotation touches every tenant's secrets
 * ({@link EncryptionKeyRotationService} uses no organization predicate), so these are
 * gated on the platform-admin operator credential (see
 * {@code security.PlatformAdminAuthenticationFilter}), not on tenant RBAC.
 * {@code SecurityConfig} enforces the {@code PLATFORM_ADMIN} authority on
 * {@code /api/v1/admin/**} before requests ever reach this controller; no org-scoped
 * JWT or API key can satisfy it.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/encryption")
@Tag(name = "Encryption Admin", description = "Encryption key management and rotation (platform-admin operator credential only)")
@SecurityRequirement(name = "platformAdminToken")
@RequiredArgsConstructor
public class EncryptionAdminController {

    private final EncryptionKeyRotationService rotationService;
    private final EncryptionKeyRegistry encryptionKeyRegistry;

    @Operation(
            summary = "Rotate encryption keys",
            description = "Re-encrypts all secrets (endpoints, incoming sources, incoming destinations) "
                    + "with the currently active encryption key version, across ALL tenants. "
                    + "Requires the platform-admin operator credential (X-Platform-Admin-Token)."
    )
    @ApiResponse(responseCode = "200", description = "Rotation completed with no errors")
    @ApiResponse(responseCode = "207", description = "Rotation completed but some records failed to re-encrypt — see 'errors'")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform-admin operator credential")
    @ApiResponse(responseCode = "409", description = "Rotation already in progress on another node")
    @PostMapping("/rotate")
    public ResponseEntity<EncryptionRotationResponse> rotateEncryptionKeys() {
        log.info("Encryption key rotation triggered by platform-admin operator credential");

        try {
            EncryptionKeyRotationService.RotationResult result = rotationService.rotateAll();

            EncryptionRotationResponse response = EncryptionRotationResponse.builder()
                    .status(result.errors() == 0 ? "completed" : "completed_with_errors")
                    .targetVersion(result.targetVersion())
                    .endpointsRotated(result.endpointsRotated())
                    .sourcesRotated(result.sourcesRotated())
                    .destinationsRotated(result.destinationsRotated())
                    .errors(result.errors())
                    .build();

            // Partial failure here can leave some tenants' secrets undecryptable — never
            // report that as a plain 200. The counter (encryption_rotation_partial_failures_total,
            // incremented in EncryptionKeyRotationService) is the alertable signal; this status
            // code is the synchronous one for whoever/whatever triggered the rotation.
            HttpStatus status = result.errors() == 0 ? HttpStatus.OK : HttpStatus.MULTI_STATUS;
            if (result.errors() > 0) {
                log.error("Encryption key rotation completed with {} error(s) — some secrets may be "
                        + "undecryptable until re-rotated. targetVersion={}", result.errors(), result.targetVersion());
            }
            return ResponseEntity.status(status).body(response);
        } catch (IllegalStateException e) {
            log.warn("Rotation lock conflict: {}", e.getMessage());
            return ResponseEntity.status(409).body(EncryptionRotationResponse.builder()
                    .status("locked")
                    .errors(-1)
                    .build());
        }
    }

    @Operation(
            summary = "Get encryption status",
            description = "Returns the active encryption key version and all available versions. "
                    + "Requires the platform-admin operator credential (X-Platform-Admin-Token)."
    )
    @ApiResponse(responseCode = "200", description = "Encryption status returned")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform-admin operator credential")
    @GetMapping("/status")
    public ResponseEntity<EncryptionStatusResponse> getEncryptionStatus() {
        EncryptionStatusResponse response = EncryptionStatusResponse.builder()
                .activeKeyVersion(encryptionKeyRegistry.getActiveVersion())
                .availableVersions(encryptionKeyRegistry.getVersions())
                .build();

        return ResponseEntity.ok(response);
    }
}
