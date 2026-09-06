package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.IncomingDestinationRequest;
import com.webhook.platform.api.dto.IncomingDestinationResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.IncomingDestinationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/incoming-sources/{sourceId}/destinations")
@Tag(name = "Incoming Destinations", description = "Incoming webhook forwarding destinations")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class IncomingDestinationController {

    private final IncomingDestinationService destinationService;

    public IncomingDestinationController(IncomingDestinationService destinationService) {
        this.destinationService = destinationService;
    }

    @Operation(summary = "Create destination", description = "Creates a new forwarding destination for the incoming source")
    @ApiResponse(responseCode = "201", description = "Destination created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<IncomingDestinationResponse> createDestination(
            @PathVariable("sourceId") UUID sourceId,
            @Valid @RequestBody IncomingDestinationRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        IncomingDestinationResponse response = destinationService.createDestination(sourceId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get destination", description = "Returns destination details by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Destination retrieved"),
            @ApiResponse(responseCode = "404", description = "Destination not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<IncomingDestinationResponse> getDestination(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        IncomingDestinationResponse response = destinationService.getDestination(id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "List destinations", description = "Returns paginated destinations for the incoming source")
    @GetMapping
    public ResponseEntity<Page<IncomingDestinationResponse>> listDestinations(
            @PathVariable("sourceId") UUID sourceId,
            @PageableDefault(size = 20) @ParameterObject Pageable pageable,
            AuthContext auth) {
        Page<IncomingDestinationResponse> response = destinationService.listDestinations(sourceId, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Update destination", description = "Updates destination configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Destination updated"),
            @ApiResponse(responseCode = "404", description = "Destination not found")
    })
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{id}")
    public ResponseEntity<IncomingDestinationResponse> updateDestination(
            @PathVariable("id") UUID id,
            @Valid @RequestBody IncomingDestinationRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        IncomingDestinationResponse response = destinationService.updateDestination(id, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Delete destination", description = "Permanently deletes the forwarding destination")
    @ApiResponse(responseCode = "204", description = "Destination deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDestination(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        destinationService.deleteDestination(id);
        return ResponseEntity.noContent().build();
    }
}
