package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.IncomingSourceRequest;
import com.webhook.platform.api.dto.IncomingSourceResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.IncomingSourceService;
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
@RequestMapping("/api/v1/projects/{projectId}/incoming-sources")
@Tag(name = "Incoming Sources", description = "Incoming webhook source configuration")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class IncomingSourceController {

    private final IncomingSourceService sourceService;

    public IncomingSourceController(IncomingSourceService sourceService) {
        this.sourceService = sourceService;
    }

    @Operation(summary = "Create incoming source", description = "Creates a new incoming webhook source for the project")
    @ApiResponse(responseCode = "201", description = "Source created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<IncomingSourceResponse> createSource(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody IncomingSourceRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        IncomingSourceResponse response = sourceService.createSource(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get incoming source", description = "Returns incoming source details by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Source retrieved"),
            @ApiResponse(responseCode = "404", description = "Source not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<IncomingSourceResponse> getSource(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        IncomingSourceResponse response = sourceService.getSource(id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "List incoming sources", description = "Returns paginated incoming sources for the project")
    @GetMapping
    public ResponseEntity<Page<IncomingSourceResponse>> listSources(
            @PathVariable("projectId") UUID projectId,
            @PageableDefault(size = 20) @ParameterObject Pageable pageable,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        Page<IncomingSourceResponse> response = sourceService.listSources(projectId, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Update incoming source", description = "Updates incoming source configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Source updated"),
            @ApiResponse(responseCode = "404", description = "Source not found")
    })
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{id}")
    public ResponseEntity<IncomingSourceResponse> updateSource(
            @PathVariable("id") UUID id,
            @Valid @RequestBody IncomingSourceRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        IncomingSourceResponse response = sourceService.updateSource(id, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Delete incoming source", description = "Disables the incoming source (soft delete)")
    @ApiResponse(responseCode = "204", description = "Source disabled")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSource(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        sourceService.deleteSource(id);
        return ResponseEntity.noContent().build();
    }
}
