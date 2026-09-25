package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.dto.DeliveryAttemptResponse;
import com.webhook.platform.api.dto.PortalDeliveryResponse;
import com.webhook.platform.api.dto.PortalEndpointRequest;
import com.webhook.platform.api.dto.PortalEndpointResponse;
import com.webhook.platform.api.dto.PortalSessionInfoResponse;
import com.webhook.platform.api.security.PortalContext;
import com.webhook.platform.api.service.PortalService;
import com.webhook.platform.api.service.billing.QuotaType;
import com.webhook.platform.api.service.billing.RequireQuota;
import io.swagger.v3.oas.annotations.Operation;
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
 * Authenticated only by a portal session, and PortalService confines every operation to the
 * session's Consumer. No access level or API-key scope: the caller has neither.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/portal")
@Tag(name = "Portal", description = "What the embedded customer portal calls, authenticated by a portal session")
@SecurityRequirement(name = "portalSession")
public class PortalController {

    private final PortalService portalService;

    @Operation(summary = "Get portal session",
            description = "The consumer and project this session is for, and the event types it can subscribe to")
    @GetMapping("/session")
    public ResponseEntity<PortalSessionInfoResponse> portalGetSession(PortalContext portal) {
        return ResponseEntity.ok(portalService.describeSession(portal));
    }

    @Operation(summary = "List portal endpoints", description = "The consumer's own endpoints")
    @GetMapping("/endpoints")
    public ResponseEntity<List<PortalEndpointResponse>> portalListEndpoints(PortalContext portal) {
        return ResponseEntity.ok(portalService.listEndpoints(portal));
    }

    @Operation(summary = "Create portal endpoint",
            description = "Registers an endpoint for the consumer. The signing secret is in this response only.")
    @ApiResponse(responseCode = "201", description = "Endpoint created")
    @ApiResponse(responseCode = "402", description = "The project has reached its plan's endpoint limit")
    @RequireQuota(QuotaType.ENDPOINTS_PER_PROJECT)
    @PostMapping("/endpoints")
    public ResponseEntity<PortalEndpointResponse> portalCreateEndpoint(
            @Valid @RequestBody PortalEndpointRequest request,
            PortalContext portal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(portalService.createEndpoint(portal, request));
    }

    @Operation(summary = "Get portal endpoint")
    @GetMapping("/endpoints/{endpointId}")
    public ResponseEntity<PortalEndpointResponse> portalGetEndpoint(
            @PathVariable("endpointId") UUID endpointId,
            PortalContext portal) {
        return ResponseEntity.ok(portalService.getEndpoint(portal, endpointId));
    }

    @Operation(summary = "Update portal endpoint", description = "Absent fields are left alone; eventTypes replaces the set")
    @PutMapping("/endpoints/{endpointId}")
    public ResponseEntity<PortalEndpointResponse> portalUpdateEndpoint(
            @PathVariable("endpointId") UUID endpointId,
            @Valid @RequestBody PortalEndpointRequest request,
            PortalContext portal) {
        return ResponseEntity.ok(portalService.updateEndpoint(portal, endpointId, request));
    }

    @Operation(summary = "Delete portal endpoint")
    @ApiResponse(responseCode = "204", description = "Endpoint deleted")
    @DeleteMapping("/endpoints/{endpointId}")
    public ResponseEntity<Void> portalDeleteEndpoint(
            @PathVariable("endpointId") UUID endpointId,
            PortalContext portal) {
        portalService.deleteEndpoint(portal, endpointId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rotate portal endpoint secret",
            description = "A new signing secret, shown in this response only. The old one keeps verifying for 24 hours.")
    @PostMapping("/endpoints/{endpointId}/rotate-secret")
    public ResponseEntity<PortalEndpointResponse> portalRotateEndpointSecret(
            @PathVariable("endpointId") UUID endpointId,
            PortalContext portal) {
        return ResponseEntity.ok(portalService.rotateSecret(portal, endpointId));
    }

    @Operation(summary = "List portal deliveries", description = "Deliveries to the consumer's endpoints, newest first")
    @GetMapping("/deliveries")
    public ResponseEntity<Page<PortalDeliveryResponse>> portalListDeliveries(
            @RequestParam(value = "endpointId", required = false) UUID endpointId,
            @RequestParam(value = "status", required = false) DeliveryStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) @ParameterObject
            Pageable pageable,
            PortalContext portal) {
        return ResponseEntity.ok(portalService.listDeliveries(portal, endpointId, status, pageable));
    }

    @Operation(summary = "List portal delivery attempts", description = "Every attempt at one delivery, with request and response")
    @GetMapping("/deliveries/{deliveryId}/attempts")
    public ResponseEntity<List<DeliveryAttemptResponse>> portalListDeliveryAttempts(
            @PathVariable("deliveryId") UUID deliveryId,
            PortalContext portal) {
        return ResponseEntity.ok(portalService.listAttempts(portal, deliveryId));
    }

    @Operation(summary = "Retry portal delivery",
            description = "Puts a failed delivery back on its retry ladder for another attempt")
    @ApiResponse(responseCode = "202", description = "Retry scheduled")
    @ApiResponse(responseCode = "409", description = "An attempt at this delivery is under way")
    @PostMapping("/deliveries/{deliveryId}/retry")
    public ResponseEntity<Void> portalRetryDelivery(
            @PathVariable("deliveryId") UUID deliveryId,
            PortalContext portal) {
        portalService.retryDelivery(portal, deliveryId);
        return ResponseEntity.accepted().build();
    }
}
