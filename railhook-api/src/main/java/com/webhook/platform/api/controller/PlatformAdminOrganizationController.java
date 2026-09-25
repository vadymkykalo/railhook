package com.webhook.platform.api.controller;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.dto.AdminAuditEntryResponse;
import com.webhook.platform.api.dto.AdminMemberResponse;
import com.webhook.platform.api.dto.AdminOrganizationResponse;
import com.webhook.platform.api.dto.AdminProjectResponse;
import com.webhook.platform.api.dto.ReinstateOrganizationRequest;
import com.webhook.platform.api.dto.SuspendOrganizationRequest;
import com.webhook.platform.api.dto.UsageResponse;
import com.webhook.platform.api.security.PlatformAdminUserAuthenticationToken;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authorization is the PLATFORM_ADMIN authority required on {@code /api/v1/admin/**}, which no
 * tenant role carries. No RequireAccess here on purpose: it resolves a membership role, and the
 * operator has none.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/organizations")
@Tag(name = "Organization Admin",
        description = "Operator view of every tenant, and suspension (platform admin only)")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "platformAdminToken")
@ProjectScopeExempt(reason = "no {projectId} in any of these paths; the operator is not scoped to a project")
@RequiredArgsConstructor
public class PlatformAdminOrganizationController {

    private static final String FORBIDDEN = "Forbidden — requires the platform admin";

    private final PlatformAdminService platformAdminService;

    @Operation(operationId = "adminListOrganizations",
            summary = "List organizations",
            description = "Every organization on this deployment, newest first. Optionally narrowed "
                    + "by name or a member's address, or to those currently suspended.")
    @ApiResponse(responseCode = "200", description = "A page of organizations")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @GetMapping
    public ResponseEntity<Page<AdminOrganizationResponse>> listOrganizations(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "suspendedOnly", defaultValue = "false") boolean suspendedOnly,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(platformAdminService.listOrganizations(search, suspendedOnly, pageable));
    }

    @Operation(operationId = "adminGetOrganization",
            summary = "Get one organization",
            description = "Plan, billing status, owner, project and member counts, events this month, "
                    + "and any suspension.")
    @ApiResponse(responseCode = "200", description = "The organization")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
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
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = "No such organization")
    @GetMapping("/{organizationId}/usage")
    public ResponseEntity<UsageResponse> getUsage(
            @PathVariable("organizationId") UUID organizationId) {
        return ResponseEntity.ok(platformAdminService.getUsage(organizationId));
    }

    @Operation(operationId = "adminListOrganizationMembers",
            summary = "Members of one organization",
            description = "Each member's address, role, membership and account status, sign-in methods, "
                    + "and when they joined and were last active.")
    @ApiResponse(responseCode = "200", description = "A page of members")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = "No such organization")
    @GetMapping("/{organizationId}/members")
    public ResponseEntity<Page<AdminMemberResponse>> listMembers(
            @PathVariable("organizationId") UUID organizationId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(platformAdminService.listMembers(organizationId, pageable));
    }

    @Operation(operationId = "adminListOrganizationProjects",
            summary = "Projects of one organization",
            description = "The organization's live projects: name and creation time, nothing they contain.")
    @ApiResponse(responseCode = "200", description = "A page of projects")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = "No such organization")
    @GetMapping("/{organizationId}/projects")
    public ResponseEntity<Page<AdminProjectResponse>> listProjects(
            @PathVariable("organizationId") UUID organizationId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(platformAdminService.listProjects(organizationId, pageable));
    }

    @Operation(operationId = "adminListOrganizationAuditLog",
            summary = "Audit log of one organization",
            description = "Who did what in the organization, newest first: action, resource, actor, outcome, "
                    + "address. Without request bodies.")
    @ApiResponse(responseCode = "200", description = "A page of audit entries")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = "No such organization")
    @GetMapping("/{organizationId}/audit-log")
    public ResponseEntity<Page<AdminAuditEntryResponse>> listAuditLog(
            @PathVariable("organizationId") UUID organizationId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(platformAdminService.listAuditLog(organizationId, pageable));
    }

    @Operation(operationId = "adminSuspendOrganization",
            summary = "Suspend an organization",
            description = "Stops the organization changing anything — ingest included — until it is "
                    + "reinstated. Reads keep working, so the tenant can sign in and be shown why. "
                    + "Independent of billing status, so a payment does not lift it. A signed-in "
                    + "platform admin's own address is recorded as suspendedBy.")
    @ApiResponse(responseCode = "200", description = "Suspended")
    @ApiResponse(responseCode = "400", description = "No reason given — the tenant is shown it")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = "No such organization")
    @Auditable(action = AuditAction.ORGANIZATION_SUSPENDED, resourceType = "Organization")
    @PostMapping("/{organizationId}/suspend")
    public ResponseEntity<AdminOrganizationResponse> suspend(
            @PathVariable("organizationId") UUID organizationId,
            @Valid @RequestBody SuspendOrganizationRequest request) {
        // A signed-in admin is recorded by their address, not a name they typed. The operator
        // token has no person behind it, so that caller names themselves.
        String suspendedBy = SecurityContextHolder.getContext().getAuthentication()
                instanceof PlatformAdminUserAuthenticationToken admin
                ? admin.getEmail()
                : request.getSuspendedBy();
        return ResponseEntity.ok(platformAdminService.suspend(organizationId, request.getReason(), suspendedBy));
    }

    @Operation(operationId = "adminReinstateOrganization",
            summary = "Reinstate an organization",
            description = "Lifts a suspension. Reinstating one that is not suspended is a no-op. The "
                    + "optional reason is kept in the organization's audit log.")
    @ApiResponse(responseCode = "200", description = "Reinstated")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = "No such organization")
    @Auditable(action = AuditAction.ORGANIZATION_REINSTATED, resourceType = "Organization")
    @PostMapping("/{organizationId}/reinstate")
    public ResponseEntity<AdminOrganizationResponse> reinstate(
            @PathVariable("organizationId") UUID organizationId,
            // Read by @Auditable, which records request bodies in the audit row's details.
            @Valid @RequestBody(required = false) ReinstateOrganizationRequest request) {
        return ResponseEntity.ok(platformAdminService.reinstate(organizationId));
    }
}
