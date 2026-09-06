package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.SubscriptionRequest;
import com.webhook.platform.api.dto.SubscriptionResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.SubscriptionService;
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
@RequestMapping("/api/v1/projects/{projectId}/subscriptions")
@Tag(name = "Subscriptions", description = "Event type subscriptions")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Operation(summary = "Create subscription", description = "Subscribes an endpoint to specific event types")
    @ApiResponse(responseCode = "201", description = "Subscription created")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody SubscriptionRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        SubscriptionResponse response = subscriptionService.createSubscription(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get subscription", description = "Returns subscription details")
    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionResponse> getSubscription(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        SubscriptionResponse response = subscriptionService.getSubscription(id);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "List subscriptions", description = "Returns all subscriptions for the project")
    @GetMapping
    public ResponseEntity<List<SubscriptionResponse>> listSubscriptions(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        List<SubscriptionResponse> response = subscriptionService.listSubscriptions(projectId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Update subscription", description = "Updates subscription configuration")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PutMapping("/{id}")
    public ResponseEntity<SubscriptionResponse> updateSubscription(
            @PathVariable("id") UUID id,
            @Valid @RequestBody SubscriptionRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        SubscriptionResponse response = subscriptionService.updateSubscription(id, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Patch subscription", description = "Partially updates subscription (e.g., toggle ordering)")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PatchMapping("/{id}")
    public ResponseEntity<SubscriptionResponse> patchSubscription(
            @PathVariable("id") UUID id,
            @RequestBody SubscriptionRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        SubscriptionResponse response = subscriptionService.updateSubscription(id, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Delete subscription", description = "Removes a subscription")
    @ApiResponse(responseCode = "204", description = "Subscription deleted")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubscription(
            @PathVariable("id") UUID id,
            AuthContext auth) {
        auth.requireWriteAccess();
        subscriptionService.deleteSubscription(id);
        return ResponseEntity.noContent().build();
    }
}
