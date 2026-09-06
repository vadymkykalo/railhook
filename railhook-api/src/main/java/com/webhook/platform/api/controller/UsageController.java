package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.UsageStatsResponse;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.service.UsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/usage")
@Tag(name = "Usage", description = "Project usage statistics and metrics")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    @Operation(operationId = "getProjectUsage", summary = "Get usage stats", description = "Returns live usage counts and daily history for the project")
    @GetMapping
    public ResponseEntity<UsageStatsResponse> getUsage(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(defaultValue = "30") int days,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(usageService.getUsage(projectId, days));
    }
}
