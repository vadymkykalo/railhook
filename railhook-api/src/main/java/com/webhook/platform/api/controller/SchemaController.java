package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.*;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.SchemaRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/schemas")
@Tag(name = "Schema Registry", description = "Event type catalog and schema versioning")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class SchemaController {

    private final SchemaRegistryService schemaRegistryService;

    public SchemaController(SchemaRegistryService schemaRegistryService) {
        this.schemaRegistryService = schemaRegistryService;
    }

    // ── Event Type Catalog ──

    @Operation(summary = "List event types", description = "Returns all registered event types for the project")
    @GetMapping
    public ResponseEntity<List<EventTypeCatalogResponse>> listEventTypes(
            @PathVariable("projectId") UUID projectId, AuthContext auth) {
        return ResponseEntity.ok(schemaRegistryService.listEventTypes(projectId));
    }

    @Operation(summary = "Create event type", description = "Registers a new event type in the catalog")
    @ApiResponse(responseCode = "201", description = "Event type created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<EventTypeCatalogResponse> createEventType(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody EventTypeCatalogRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(schemaRegistryService.createEventType(projectId, request));
    }

    @Operation(summary = "Get event type", description = "Returns event type details")
    @GetMapping("/{eventTypeId}")
    public ResponseEntity<EventTypeCatalogResponse> getEventType(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            AuthContext auth) {
        return ResponseEntity.ok(schemaRegistryService.getEventType(eventTypeId));
    }

    @Operation(summary = "Update event type", description = "Updates event type description")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{eventTypeId}")
    public ResponseEntity<EventTypeCatalogResponse> updateEventType(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            @Valid @RequestBody EventTypeCatalogRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        return ResponseEntity.ok(schemaRegistryService.updateEventType(eventTypeId, request));
    }

    @Operation(summary = "Delete event type", description = "Deletes event type and all its schema versions")
    @ApiResponse(responseCode = "204", description = "Event type deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{eventTypeId}")
    public ResponseEntity<Void> deleteEventType(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            AuthContext auth) {
        auth.requireWriteAccess();
        schemaRegistryService.deleteEventType(eventTypeId);
        return ResponseEntity.noContent().build();
    }

    // ── Schema Versions ──

    @Operation(summary = "List schema versions", description = "Returns all schema versions for an event type")
    @GetMapping("/{eventTypeId}/versions")
    public ResponseEntity<List<EventSchemaVersionResponse>> listVersions(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            AuthContext auth) {
        return ResponseEntity.ok(schemaRegistryService.listSchemaVersions(eventTypeId));
    }

    @Operation(summary = "Create schema version", description = "Uploads a new JSON Schema version (created as DRAFT)")
    @ApiResponse(responseCode = "201", description = "Schema version created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{eventTypeId}/versions")
    public ResponseEntity<EventSchemaVersionResponse> createVersion(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            @Valid @RequestBody EventSchemaVersionRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(schemaRegistryService.createSchemaVersion(eventTypeId, request, auth.userId()));
    }

    @Operation(summary = "Get schema version", description = "Returns schema version details")
    @GetMapping("/{eventTypeId}/versions/{versionId}")
    public ResponseEntity<EventSchemaVersionResponse> getVersion(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            @PathVariable("versionId") UUID versionId,
            AuthContext auth) {
        return ResponseEntity.ok(schemaRegistryService.getSchemaVersion(versionId));
    }

    @Operation(summary = "Promote schema to ACTIVE", description = "Promotes a DRAFT schema to ACTIVE, deprecating the previous active version")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{eventTypeId}/versions/{versionId}/promote")
    public ResponseEntity<EventSchemaVersionResponse> promoteVersion(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            @PathVariable("versionId") UUID versionId,
            AuthContext auth) {
        auth.requireWriteAccess();
        return ResponseEntity.ok(schemaRegistryService.promoteSchema(versionId));
    }

    @Operation(summary = "Deprecate schema version", description = "Sets schema version status to DEPRECATED")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{eventTypeId}/versions/{versionId}/deprecate")
    public ResponseEntity<EventSchemaVersionResponse> deprecateVersion(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            @PathVariable("versionId") UUID versionId,
            AuthContext auth) {
        auth.requireWriteAccess();
        return ResponseEntity.ok(schemaRegistryService.deprecateSchema(versionId));
    }

    // ── Schema Changes ──

    @Operation(summary = "List all schema changes", description = "Returns the diff history across all event types in the project")
    @GetMapping("/changes")
    public ResponseEntity<List<SchemaChangeResponse>> listProjectChanges(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        return ResponseEntity.ok(schemaRegistryService.listProjectSchemaChanges(projectId));
    }

    @Operation(summary = "List schema changes for event type", description = "Returns the diff history for a specific event type")
    @GetMapping("/{eventTypeId}/changes")
    public ResponseEntity<List<SchemaChangeResponse>> listChanges(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            AuthContext auth) {
        return ResponseEntity.ok(schemaRegistryService.listSchemaChanges(eventTypeId));
    }
}
