package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.SharedDebugLinkPublicResponse;
import com.webhook.platform.api.dto.SharedDebugLinkRequest;
import com.webhook.platform.api.dto.SharedDebugLinkResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.SharedDebugLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Debug Links", description = "Shareable debug links with PII-safe payloads")
public class SharedDebugLinkController {

    private final SharedDebugLinkService debugLinkService;

    @Operation(summary = "Create debug link", description = "Creates a shareable link for an event with sanitized payload")
    @ApiResponse(responseCode = "201", description = "Link created")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "apiKey")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/api/v1/projects/{projectId}/events/{eventId}/debug-links")
    public ResponseEntity<SharedDebugLinkResponse> createLink(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventId") UUID eventId,
            @Valid @RequestBody SharedDebugLinkRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        SharedDebugLinkResponse response = debugLinkService.createLink(
                projectId, eventId, request, auth.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List debug links for event", description = "Returns all debug links for a specific event")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "apiKey")
    @GetMapping("/api/v1/projects/{projectId}/events/{eventId}/debug-links")
    public ResponseEntity<List<SharedDebugLinkResponse>> listLinksForEvent(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventId") UUID eventId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(debugLinkService.listLinksForEvent(projectId, eventId));
    }

    @Operation(summary = "Delete debug link", description = "Revokes a shared debug link")
    @ApiResponse(responseCode = "204", description = "Link deleted")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "apiKey")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/api/v1/projects/{projectId}/debug-links/{linkId}")
    public ResponseEntity<Void> deleteLink(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("linkId") UUID linkId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        debugLinkService.deleteLink(projectId, linkId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "View shared debug link (public)", description = "Public endpoint — returns sanitized event payload. No authentication required.")
    @ApiResponse(responseCode = "200", description = "Sanitized event data")
    @GetMapping("/api/v1/public/debug/{token}")
    public ResponseEntity<SharedDebugLinkPublicResponse> viewPublicLink(
            @PathVariable("token") String token) {
        return ResponseEntity.ok(debugLinkService.viewPublicLink(token));
    }
}
