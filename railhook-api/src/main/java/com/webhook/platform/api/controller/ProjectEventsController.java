package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.EventDiffResponse;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.EventResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.service.EventDiffService;
import com.webhook.platform.api.service.EventService;
import com.webhook.platform.api.service.PiiMaskingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/events")
@Tag(name = "Events", description = "Event history and test events")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class ProjectEventsController {

    private final EventService eventService;
    private final EventDiffService eventDiffService;
    private final PiiMaskingService piiMaskingService;

    public ProjectEventsController(EventService eventService,
                                   EventDiffService eventDiffService,
                                   PiiMaskingService piiMaskingService) {
        this.eventService = eventService;
        this.eventDiffService = eventDiffService;
        this.piiMaskingService = piiMaskingService;
    }

    @Operation(operationId = "listEvents", summary = "List events", description = "Returns paginated event history for the project")
    @GetMapping
    public ResponseEntity<Page<EventResponse>> listEvents(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @ParameterObject Pageable pageable,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        Page<EventResponse> response = eventService.listEvents(projectId, eventType, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "getEvent", summary = "Get event", description = "Returns event details by ID")
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEvent(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        EventResponse response = eventService.getEvent(projectId, id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get event with sanitized payload", description = "Returns event details with PII-masked payload")
    @GetMapping("/{id}/sanitized")
    public ResponseEntity<EventResponse> getEventSanitized(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        EventResponse response = eventService.getEvent(projectId, id);
        String sanitized = piiMaskingService.sanitizePayload(projectId, response.getPayload());
        response.setPayload(sanitized);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Diff two events", description = "Computes a structural diff between two events of the same project")
    @GetMapping("/diff")
    public ResponseEntity<EventDiffResponse> diffEvents(
            @PathVariable("projectId") UUID projectId,
            @RequestParam("left") UUID leftEventId,
            @RequestParam("right") UUID rightEventId,
            @RequestParam(value = "sanitize", defaultValue = "true") boolean sanitize,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        EventDiffResponse response = eventDiffService.diff(projectId, leftEventId, rightEventId, sanitize);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Send test event", description = "Sends a test event through the webhook pipeline")
    @ApiResponse(responseCode = "201", description = "Test event created")
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/test")
    public ResponseEntity<EventResponse> sendTestEvent(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody EventIngestRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        
        log.info("Sending test event type: {} for project: {}", request.getType(), projectId);
        EventResponse response = eventService.sendTestEvent(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
