package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.ConsumerRequest;
import com.webhook.platform.api.dto.ConsumerResponse;
import com.webhook.platform.api.dto.EndpointResponse;
import com.webhook.platform.api.dto.PortalSessionRequest;
import com.webhook.platform.api.dto.PortalSessionResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.ConsumerService;
import com.webhook.platform.api.service.EndpointService;
import com.webhook.platform.api.service.PortalSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Consumers — the customer's own users — and the portal sessions the customer opens for them.
 *
 * <p>Called from the customer's backend with a project API key, or from the dashboard. Opening a
 * session mints a credential that can register Endpoints in the project, so it is a write, and a
 * READ_ONLY key cannot do it.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/consumers")
@Tag(name = "Consumers", description = "Your own users, their endpoints, and the portal sessions you open for them")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class ConsumerController {

    private final ConsumerService consumerService;
    private final EndpointService endpointService;
    private final PortalSessionService portalSessionService;

    @Operation(summary = "Create consumer", description = "Registers one of your users, identified by your own id")
    @ApiResponse(responseCode = "201", description = "Consumer created")
    @ApiResponse(responseCode = "409", description = "A consumer with this externalId already exists")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @PostMapping
    public ResponseEntity<ConsumerResponse> createConsumer(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody ConsumerRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(consumerService.createConsumer(projectId, request));
    }

    @Operation(summary = "List consumers", description = "Returns the project's consumers; externalId narrows it to one")
    @GetMapping
    public ResponseEntity<Page<ConsumerResponse>> listConsumers(
            @PathVariable("projectId") UUID projectId,
            @Parameter(description = "Your own id for the user") @RequestParam(value = "externalId", required = false)
            String externalId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) @ParameterObject
            Pageable pageable,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(consumerService.listConsumers(projectId, externalId, pageable));
    }

    @Operation(summary = "Get consumer")
    @GetMapping("/{consumerId}")
    public ResponseEntity<ConsumerResponse> getConsumer(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("consumerId") UUID consumerId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(consumerService.getConsumer(projectId, consumerId));
    }

    @Operation(summary = "Update consumer")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @PutMapping("/{consumerId}")
    public ResponseEntity<ConsumerResponse> updateConsumer(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("consumerId") UUID consumerId,
            @Valid @RequestBody ConsumerRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(consumerService.updateConsumer(projectId, consumerId, request));
    }

    @Operation(summary = "Delete consumer",
            description = "Deletes the consumer, deletes its endpoints and ends its portal sessions")
    @ApiResponse(responseCode = "204", description = "Consumer deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @DeleteMapping("/{consumerId}")
    public ResponseEntity<Void> deleteConsumer(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("consumerId") UUID consumerId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        consumerService.deleteConsumer(projectId, consumerId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List consumer endpoints", description = "The endpoints registered for this consumer")
    @GetMapping("/{consumerId}/endpoints")
    public ResponseEntity<List<EndpointResponse>> listConsumerEndpoints(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("consumerId") UUID consumerId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(endpointService.listEndpointsOfConsumer(projectId, consumerId));
    }

    @Operation(summary = "Create portal session",
            description = "Opens the customer portal for this consumer. The response carries the token once; "
                    + "only its hash is stored.")
    @ApiResponse(responseCode = "201", description = "Session created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @PostMapping("/{consumerId}/portal-sessions")
    public ResponseEntity<PortalSessionResponse> createPortalSession(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("consumerId") UUID consumerId,
            @Valid @RequestBody(required = false) PortalSessionRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(portalSessionService.createSession(projectId, consumerId, request));
    }

    @Operation(summary = "Revoke portal sessions", description = "Ends every open portal session of this consumer")
    @ApiResponse(responseCode = "204", description = "Sessions revoked")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @DeleteMapping("/{consumerId}/portal-sessions")
    public ResponseEntity<Void> revokePortalSessions(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("consumerId") UUID consumerId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        portalSessionService.revokeSessions(projectId, consumerId);
        return ResponseEntity.noContent().build();
    }
}
