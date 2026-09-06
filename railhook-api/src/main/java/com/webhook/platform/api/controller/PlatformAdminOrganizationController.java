package com.webhook.platform.api.controller;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.dto.AdminOrganizationResponse;
import com.webhook.platform.api.dto.SuspendOrganizationRequest;
import com.webhook.platform.api.dto.UsageResponse;
import com.webhook.platform.api.security.ProjectScopeExempt;
import com.webhook.platform.api.service.PlatformAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The operator's view of the tenants on this deployment.
 *
 * <p>Until this existed, {@code /api/v1/admin/**} was a single endpoint that rotates encryption
 * keys, and everything else an operator might need — who is on this instance, why did this
 * customer's deliveries stop, make this one stop — was psql. That is a bad place to answer a
 * support question and a worse place to act on an abuse report.
 *
 * <p>Gated on the platform-admin operator credential, like its neighbour: {@code SecurityConfig}
 * requires the {@code PLATFORM_ADMIN} authority across {@code /api/v1/admin/**}, which no
 * tenant JWT or API key can carry however privileged its role.
 *
 * <p>Declares no {@link com.webhook.platform.api.security.RequireAccess}, deliberately. That
 * annotation resolves a membership role, and the operator has none — a platform-admin request
 * to a handler declaring an access level is refused by design, because such a handler is a
 * tenant endpoint. Authorization here is the credential itself.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/organizations")
@Tag(name = "Organization Admin",
        description = "Operator view of every tenant, and suspension (platform-admin operator credential only)")
@SecurityRequirement(name = "platformAdminToken")
@ProjectScopeExempt(reason = "no {projectId} in any of these paths; the operator is not scoped to a project")
@RequiredArgsConstructor
public class PlatformAdminOrganizationController {

    private final PlatformAdminService platformAdminService;

    @Operation(operationId = "adminListOrganizations",
            summary = "List organizations",
            description = "Every organization on this deployment, newest first. Optionally narrowed "
                    + "by name, or to those currently suspended. Requires X-Platform-Admin-Token.")
    @ApiResponse(responseCode = "200", description = "A page of organizations")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform-admin operator credential")
    @GetMapping
    public ResponseEntity<Page<AdminOrganizationResponse>> listOrganizations(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "suspendedOnly", defaultValue = "false") boolean suspendedOnly,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(platformAdminService.listOrganizations(search, suspendedOnly, pageable));
    }

    @Operation(operationId = "adminGetOrganization",
            summary = "Get one organization",
            description = "Plan, billing status, project and member counts, and any suspension.")
    @ApiResponse(responseCode = "200", description = "The organization")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform-admin operator credential")
    @ApiResponse(responseCode = "404", description = "No such organization")
    @GetMapping("/{organizationId}")
    public ResponseEntity<AdminOrganizationResponse> getOrganization(
            @PathVariable("organizationId") UUID organizationId) {
        return ResponseEntity.ok(platformAdminService.getOrganization(organizationId));
    }

    @Operation(operationId = "adminGetOrganizationUsage",
            summary = "What one organization has used",
            description = "Events this billing period, endpoints, projects and members, each "
                    + "against the limit their plan allows — the same numbers the tenant sees on "
                    + "their own billing page, so a support conversation is about one set of "
                    + "figures. Carries no customer data: counts and limits only.")
    @ApiResponse(responseCode = "200", description = "Usage against the plan")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform-admin operator credential")
    @ApiResponse(responseCode = "404", description = "No such organization")
    @GetMapping("/{organizationId}/usage")
    public ResponseEntity<UsageResponse> getUsage(
            @PathVariable("organizationId") UUID organizationId) {
        return ResponseEntity.ok(platformAdminService.getUsage(organizationId));
    }

    @Operation(operationId = "adminSuspendOrganization",
            summary = "Suspend an organization",
            description = "Stops the organization changing anything — ingest included — until it is "
                    + "reinstated. Reads keep working, so the tenant can sign in and be shown why. "
                    + "Independent of billing status, so a payment does not lift it.")
    @ApiResponse(responseCode = "200", description = "Suspended")
    @ApiResponse(responseCode = "400", description = "No reason given — the tenant is shown it")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform-admin operator credential")
    @ApiResponse(responseCode = "404", description = "No such organization")
    @Auditable(action = AuditAction.ORGANIZATION_SUSPENDED, resourceType = "Organization")
    @PostMapping("/{organizationId}/suspend")
    public ResponseEntity<AdminOrganizationResponse> suspend(
            @PathVariable("organizationId") UUID organizationId,
            @Valid @RequestBody SuspendOrganizationRequest request) {
        return ResponseEntity.ok(platformAdminService.suspend(
                organizationId, request.getReason(), request.getSuspendedBy()));
    }

    @Operation(operationId = "adminReinstateOrganization",
            summary = "Reinstate an organization",
            description = "Lifts a suspension. Reinstating one that is not suspended is a no-op.")
    @ApiResponse(responseCode = "200", description = "Reinstated")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform-admin operator credential")
    @ApiResponse(responseCode = "404", description = "No such organization")
    @Auditable(action = AuditAction.ORGANIZATION_REINSTATED, resourceType = "Organization")
    @PostMapping("/{organizationId}/reinstate")
    public ResponseEntity<AdminOrganizationResponse> reinstate(
            @PathVariable("organizationId") UUID organizationId) {
        return ResponseEntity.ok(platformAdminService.reinstate(organizationId));
    }
}
