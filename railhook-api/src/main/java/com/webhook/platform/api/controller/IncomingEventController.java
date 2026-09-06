package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.IncomingBulkReplayRequest;
import com.webhook.platform.api.dto.IncomingBulkReplayResponse;
import com.webhook.platform.api.dto.IncomingEventResponse;
import com.webhook.platform.api.dto.IncomingForwardAttemptResponse;
import com.webhook.platform.api.dto.ReplayEventResponse;
import jakarta.validation.Valid;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.IncomingEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/incoming-events")
@Tag(name = "Incoming Events", description = "Incoming webhook events monitoring")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class IncomingEventController {

    private final IncomingEventService eventService;

    public IncomingEventController(IncomingEventService eventService) {
        this.eventService = eventService;
    }

    @Operation(operationId = "listIncomingEvents", summary = "List incoming events", description = "Returns paginated incoming events for the project")
    @ApiResponse(responseCode = "200", description = "Events retrieved")
    @GetMapping
    public ResponseEntity<Page<IncomingEventResponse>> listEvents(
            @PathVariable("projectId") UUID projectId,
            @Parameter(description = "Filter by incoming source ID") @RequestParam(value = "sourceId", required = false) UUID sourceId,
            @PageableDefault(size = 20, sort = "receivedAt") @ParameterObject Pageable pageable,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        Page<IncomingEventResponse> response = eventService.listEvents(
                projectId, sourceId, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "getIncomingEvent", summary = "Get incoming event", description = "Returns incoming event details by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event retrieved"),
            @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<IncomingEventResponse> getEvent(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        IncomingEventResponse response = eventService.getEvent(id, auth);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get event forward attempts", description = "Returns forwarding attempts for an incoming event")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempts retrieved"),
            @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @GetMapping("/{id}/attempts")
    public ResponseEntity<Page<IncomingForwardAttemptResponse>> getEventAttempts(
            @PathVariable("id") UUID id,
            @PageableDefault(size = 20) @ParameterObject Pageable pageable,
            AuthContext auth) {
        Page<IncomingForwardAttemptResponse> response = eventService.getEventAttempts(
                id, auth, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Replay incoming event", description = "Re-sends the incoming event to all enabled destinations")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event replayed"),
            @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{id}/replay")
    public ResponseEntity<ReplayEventResponse> replayEvent(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        int replayed = eventService.replayEvent(id, auth);
        return ResponseEntity.ok(ReplayEventResponse.builder()
                .status("replayed")
                .eventId(id)
                .destinationsCount(replayed)
                .build());
    }

    @Operation(summary = "Bulk replay incoming events",
            description = "Re-sends multiple incoming events to all enabled destinations. " +
                    "Filter by source, time range, or explicit event IDs.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bulk replay completed"),
            @ApiResponse(responseCode = "404", description = "Source not found")
    })
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/bulk-replay")
    public ResponseEntity<IncomingBulkReplayResponse> bulkReplay(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody IncomingBulkReplayRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        IncomingBulkReplayResponse response = eventService.bulkReplay(
                projectId, request);
        return ResponseEntity.ok(response);
    }
}
