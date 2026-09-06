package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.AlertEventResponse;
import com.webhook.platform.api.dto.AlertRuleRequest;
import com.webhook.platform.api.dto.AlertRuleResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/alerts")
@Tag(name = "Alerts", description = "Alert rules and fired alert events")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    // ─── Rules ──────────────────────────────────────────────────────────

    @Operation(operationId = "listAlertRules", summary = "List alert rules",
            description = "The conditions this project alerts on, and the channels each notifies.")
    @GetMapping("/rules")
    public ResponseEntity<List<AlertRuleResponse>> listRules(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(alertService.listRules(projectId));
    }

    @Operation(operationId = "createAlertRule", summary = "Create alert rule",
            description = "Defines what to watch, the threshold that fires it, and where to send it.")
    @ApiResponse(responseCode = "201", description = "Rule created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/rules")
    public ResponseEntity<AlertRuleResponse> createRule(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody AlertRuleRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(alertService.createRule(projectId, request));
    }

    @Operation(operationId = "updateAlertRule", summary = "Update alert rule",
            description = "Changes the threshold, the channels or the enabled state. Alerts already "
                    + "fired are left as they are.")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/rules/{ruleId}")
    public ResponseEntity<AlertRuleResponse> updateRule(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("ruleId") UUID ruleId,
            @Valid @RequestBody AlertRuleRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(alertService.updateRule(projectId, ruleId, request));
    }

    @Operation(operationId = "deleteAlertRule", summary = "Delete alert rule",
            description = "Removes the rule. The alerts it has already fired stay in the history.")
    @ApiResponse(responseCode = "204", description = "Rule deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("ruleId") UUID ruleId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        alertService.deleteRule(projectId, ruleId);
        return ResponseEntity.noContent().build();
    }

    // ─── Events ─────────────────────────────────────────────────────────

    @Operation(operationId = "listAlertEvents", summary = "List alert events (fired alerts)",
            description = "Times a rule actually fired, newest first, resolved and unresolved alike.")
    @GetMapping("/events")
    public ResponseEntity<Page<AlertEventResponse>> listEvents(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(alertService.listEvents(projectId, page, size));
    }

    @Operation(summary = "Count unresolved alerts",
            description = "How many fired alerts nobody has resolved — for a badge, not a report.")
    @GetMapping("/events/unresolved-count")
    public ResponseEntity<Map<String, Long>> countUnresolved(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        long count = alertService.countUnresolved(projectId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @Operation(summary = "Resolve a single alert event",
            description = "Marks one fired alert as dealt with. The rule keeps watching.")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/events/{eventId}/resolve")
    public ResponseEntity<Void> resolveEvent(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventId") UUID eventId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        alertService.resolveEvent(projectId, eventId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Resolve all unresolved alert events",
            description = "Clears the whole backlog at once, for when a known outage fired many.")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/events/resolve-all")
    public ResponseEntity<Map<String, Integer>> resolveAll(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        int resolved = alertService.resolveAll(projectId);
        return ResponseEntity.ok(Map.of("resolved", resolved));
    }
}
