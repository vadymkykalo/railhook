package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.ReplayEstimateResponse;
import com.webhook.platform.api.dto.ReplayRequest;
import com.webhook.platform.api.dto.ReplaySessionResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.ReplayService;
import com.webhook.platform.api.service.billing.RequireFeature;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/replay")
@Tag(name = "Event Time Machine", description = "Replay events by time range and filters")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class ReplayController {

    private final ReplayService replayService;

    @Operation(summary = "Estimate replay", description = "Dry-run: count matching events and estimate deliveries without executing")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @PostMapping("/estimate")
    public ResponseEntity<ReplayEstimateResponse> estimate(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody ReplayRequest request,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        ReplayEstimateResponse estimate = replayService.estimate(projectId, request);
        return ResponseEntity.ok(estimate);
    }

    @Operation(operationId = "createReplaySession", summary = "Create replay session", description = "Start async replay of events matching the criteria")
    @ApiResponse(responseCode = "201", description = "Replay session created and processing started")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireFeature("replay")
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<ReplaySessionResponse> create(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody ReplayRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        ReplaySessionResponse session = replayService.create(projectId, request, auth.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @Operation(operationId = "getReplaySession", summary = "Get replay session", description = "Returns current status and progress of a replay session")
    @GetMapping("/{sessionId}")
    public ResponseEntity<ReplaySessionResponse> get(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        ReplaySessionResponse session = replayService.get(projectId, sessionId);
        return ResponseEntity.ok(session);
    }

    @Operation(operationId = "listReplaySessions", summary = "List replay sessions", description = "Returns paginated history of replay sessions for the project")
    @GetMapping
    public ResponseEntity<Page<ReplaySessionResponse>> list(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<ReplaySessionResponse> sessions = replayService.list(projectId, pageable);
        return ResponseEntity.ok(sessions);
    }

    @Operation(summary = "Cancel replay session", description = "Requests cancellation of a running replay session")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{sessionId}/cancel")
    public ResponseEntity<ReplaySessionResponse> cancel(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("sessionId") UUID sessionId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        ReplaySessionResponse session = replayService.cancel(projectId, sessionId);
        return ResponseEntity.ok(session);
    }
}
