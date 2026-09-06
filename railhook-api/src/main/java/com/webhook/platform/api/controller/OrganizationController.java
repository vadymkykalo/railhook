package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.GdprExportDto;
import com.webhook.platform.api.dto.OrganizationResponse;
import com.webhook.platform.api.dto.UpdateOrganizationRequest;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.service.GdprExportService;
import com.webhook.platform.api.service.OrganizationService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/orgs")
@Tag(name = "Organizations", description = "Organization management")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final GdprExportService gdprExportService;

    public OrganizationController(OrganizationService organizationService,
                                  GdprExportService gdprExportService) {
        this.organizationService = organizationService;
        this.gdprExportService = gdprExportService;
    }

    @Operation(summary = "List user organizations", description = "Returns all organizations the user belongs to")
    @GetMapping
    public ResponseEntity<List<OrganizationResponse>> getUserOrganizations(AuthContext auth) {
        List<OrganizationResponse> response = organizationService.getUserOrganizations(auth.requireUserId());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get organization", description = "Returns organization details")
    @GetMapping("/{orgId}")
    public ResponseEntity<OrganizationResponse> getOrganization(
            @PathVariable("orgId") UUID orgId,
            AuthContext auth) {
        OrganizationResponse response = organizationService.getOrganization( auth.requireUserId());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Update organization", description = "Updates organization details (owner only)")
    @RequireAccess(AccessLevel.OWNER)
@PutMapping("/{orgId}")
    public ResponseEntity<OrganizationResponse> updateOrganization(
            @PathVariable("orgId") UUID orgId,
            @Valid @RequestBody UpdateOrganizationRequest request,
            AuthContext auth) {
        auth.requireOwnerAccess();
        OrganizationResponse response = organizationService.updateOrganization( request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Export organization data (GDPR Article 20)",
            description = "Exports all organization data in machine-readable JSON format. "
                    + "Includes organization info, members, projects, endpoints, subscriptions, "
                    + "incoming sources/destinations, API keys (metadata only), and audit logs. "
                    + "No decrypted secrets are included. Owner only.")
    @ApiResponse(responseCode = "200", description = "Data export as JSON")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires OWNER role")
    // A read, so no ratchet asks it to declare anything — MutatingHandlerAccessDeclarationTest
    // covers state-changing handlers only, and most reads legitimately require nothing. This one
    // returns every member, project, endpoint and audit row in the organization, and until this
    // annotation its OWNER requirement lived only in the call below, while its sibling
    // deleteOrganization declared it.
    @RequireAccess(AccessLevel.OWNER)
    @GetMapping("/{orgId}/export")
    public ResponseEntity<GdprExportDto> exportOrganizationData(
            @PathVariable("orgId") UUID orgId,
            AuthContext auth) {
        auth.requireOwnerAccess();
        if (!orgId.equals(auth.organizationId())) {
            throw new com.webhook.platform.api.exception.ForbiddenException("Access denied");
        }
        GdprExportDto export = gdprExportService.exportOrganizationData();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"org-" + orgId + "-export.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(export);
    }

    @Operation(summary = "Delete organization (GDPR)",
            description = "Permanently deletes the organization and all associated data. " +
                    "This action is irreversible. Owner only.")
    @ApiResponse(responseCode = "204", description = "Organization deleted")
    @RequireAccess(AccessLevel.OWNER)
@DeleteMapping("/{orgId}")
    public ResponseEntity<Void> deleteOrganization(
            @PathVariable("orgId") UUID orgId,
            AuthContext auth) {
        auth.requireOwnerAccess();
        if (!orgId.equals(auth.organizationId())) {
            throw new com.webhook.platform.api.exception.ForbiddenException("Access denied");
        }
        organizationService.deleteOrganization();
        return ResponseEntity.noContent().build();
    }
}
