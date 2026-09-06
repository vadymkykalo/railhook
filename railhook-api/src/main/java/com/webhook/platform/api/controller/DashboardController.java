package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.dto.DashboardStatsResponse;
import com.webhook.platform.api.dto.OnboardingStatusResponse;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.service.AnalyticsService;
import com.webhook.platform.api.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Project dashboard, statistics and analytics")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class DashboardController {
    
    private final DashboardService dashboardService;
    private final AnalyticsService analyticsService;
    
    public DashboardController(DashboardService dashboardService, AnalyticsService analyticsService) {
        this.dashboardService = dashboardService;
        this.analyticsService = analyticsService;
    }
    
    @Operation(summary = "Get project dashboard", description = "Returns delivery statistics for a project")
    @GetMapping("/projects/{projectId}")
    public ResponseEntity<DashboardStatsResponse> getProjectDashboard(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        log.info("Dashboard stats request for projectId: {}", projectId);
        DashboardStatsResponse stats = dashboardService.getProjectStats(projectId);
        return ResponseEntity.ok(stats);
    }

    @Operation(summary = "Get onboarding status", description = "Returns server-derived onboarding checklist status")
    @GetMapping("/projects/{projectId}/onboarding")
    public ResponseEntity<OnboardingStatusResponse> getOnboardingStatus(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        OnboardingStatusResponse status = dashboardService.getOnboardingStatus(projectId);
        return ResponseEntity.ok(status);
    }

    @Operation(summary = "Get project analytics", description = "Returns detailed analytics with time series data")
    @GetMapping("/projects/{projectId}/analytics")
    public ResponseEntity<AnalyticsResponse> getProjectAnalytics(
            @PathVariable("projectId") UUID projectId,
            @Parameter(description = "Time period: 24h, 7d, 30d") @RequestParam(name = "period", defaultValue = "24h") String period,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        log.info("Analytics request for projectId: {}, period: {}", projectId, period);
        AnalyticsResponse analytics = analyticsService.getAnalytics(projectId, period);
        return ResponseEntity.ok(analytics);
    }
}
