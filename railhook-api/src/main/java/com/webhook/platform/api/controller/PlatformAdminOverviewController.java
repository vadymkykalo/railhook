package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.AdminUserResponse;
import com.webhook.platform.api.dto.PlatformOverviewResponse;
import com.webhook.platform.api.security.ProjectScopeExempt;
import com.webhook.platform.api.service.PlatformAdminOverviewService;
import com.webhook.platform.api.service.PlatformAdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** No {@code @RequireAccess}: no membership role reaches /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Platform Admin", description = "Deployment-wide overview and accounts (platform admin only)")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "platformAdminToken")
@ProjectScopeExempt(reason = "no {projectId} in any of these paths; the platform admin is not scoped to a project")
@RequiredArgsConstructor
public class PlatformAdminOverviewController {

    private final PlatformAdminOverviewService overviewService;
    private final PlatformAdminUserService userService;

    @Operation(operationId = "adminGetOverview",
            summary = "Deployment overview",
            description = "Organizations, accounts and sign-ups, event and delivery volume, active tunnels, "
                    + "organizations at 80% or more of their monthly event limit, and the most recent sign-ups. "
                    + "Counts only.")
    @ApiResponse(responseCode = "200", description = "The overview")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform admin")
    @ApiResponse(responseCode = "429", description = "Too many platform admin requests")
    @GetMapping("/overview")
    public ResponseEntity<PlatformOverviewResponse> overview() {
        return ResponseEntity.ok(overviewService.overview());
    }

    @Operation(operationId = "adminListUsers",
            summary = "List accounts",
            description = "Every account on this deployment, newest first, optionally narrowed by address or "
                    + "name: verification, status, sign-in methods, organizations and last activity. Never "
                    + "carries a password hash or any token.")
    @ApiResponse(responseCode = "200", description = "A page of accounts")
    @ApiResponse(responseCode = "403", description = "Forbidden — requires the platform admin")
    @ApiResponse(responseCode = "429", description = "Too many platform admin requests")
    @GetMapping("/users")
    public ResponseEntity<Page<AdminUserResponse>> listUsers(
            @RequestParam(value = "search", required = false) String search,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(userService.listUsers(search, pageable));
    }
}
