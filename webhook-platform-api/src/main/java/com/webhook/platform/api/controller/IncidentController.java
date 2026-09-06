package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.IncidentCountsResponse;
import com.webhook.platform.api.dto.IncidentRequest;
import com.webhook.platform.api.dto.IncidentResponse;
import com.webhook.platform.api.dto.TimelineEntryRequest;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.IncidentService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/incidents")
@Tag(name = "Incidents", description = "Incident management with timeline and RCA")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    @Operation(operationId = "listIncidents", summary = "List incidents",
            description = "Incidents in the project, newest first, filterable by status and severity.")
    @GetMapping
    public ResponseEntity<Page<IncidentResponse>> list(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(incidentService.listIncidents(projectId, openOnly, page, size));
    }

    @Operation(operationId = "getIncident", summary = "Get incident with timeline",
            description = "The incident and every entry recorded against it, in the order they happened.")
    @GetMapping("/{incidentId}")
    public ResponseEntity<IncidentResponse> get(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("incidentId") UUID incidentId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(incidentService.getIncident(projectId, incidentId));
    }

    @Operation(operationId = "createIncident", summary = "Create incident",
            description = "Opens an incident and records its first timeline entry.")
    @ApiResponse(responseCode = "201", description = "Incident created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<IncidentResponse> create(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody IncidentRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(incidentService.createIncident(projectId, request));
    }

    @Operation(operationId = "updateIncident", summary = "Update incident (status, RCA notes, severity)",
            description = "A change of status is itself recorded on the timeline, so the history "
                    + "stays readable after the fact.")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{incidentId}")
    public ResponseEntity<IncidentResponse> update(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("incidentId") UUID incidentId,
            @Valid @RequestBody IncidentRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(incidentService.updateIncident(projectId, incidentId, request));
    }

    @Operation(summary = "Add timeline entry to incident",
            description = "Appends a note, an action taken, or an observation.")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{incidentId}/timeline")
    public ResponseEntity<IncidentResponse> addTimeline(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("incidentId") UUID incidentId,
            @Valid @RequestBody TimelineEntryRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(incidentService.addTimelineEntry(projectId, incidentId, request));
    }

    @Operation(summary = "Count open incidents",
            description = "How many incidents are not yet resolved, how many are being "
                    + "investigated, and how many of them are critical — for a badge and the "
                    + "three tiles above the list, not a report. Every count spans the project, "
                    + "which is what separates them from anything derived from a page of it.")
    @GetMapping("/open-count")
    public ResponseEntity<IncidentCountsResponse> countOpen(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(incidentService.countUnresolved(projectId));
    }
}
