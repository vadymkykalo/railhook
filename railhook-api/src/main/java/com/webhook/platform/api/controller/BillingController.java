package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.*;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.service.billing.BillingOverviewService;
import com.webhook.platform.api.service.billing.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/billing")
@Tag(name = "Billing", description = "Plan catalog, usage, and subscription management")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final BillingOverviewService billingOverviewService;

    // ── Plan catalog (public) ─────────────────────────────────────

    @Operation(summary = "List available plans", description = "Returns all active plans with their limits and pricing")
    @GetMapping("/plans")
    public ResponseEntity<List<PlanResponse>> listPlans() {
        return ResponseEntity.ok(billingOverviewService.catalog());
    }

    // ── Organization billing ──────────────────────────────────────

    @Operation(summary = "Get organization billing info", description = "Returns current plan, billing status, and usage snapshot")
    @GetMapping("/organization")
    public ResponseEntity<OrganizationBillingResponse> getOrganizationBilling(AuthContext auth) {
        auth.requireJwt();
        return ResponseEntity.ok(billingOverviewService.organizationBilling());
    }

    @Operation(summary = "Update organization billing info", description = "Updates billing email for the organization (owner only)")
    @RequireAccess(AccessLevel.OWNER)
@PutMapping("/organization")
    public ResponseEntity<OrganizationBillingResponse> updateBillingInfo(
            @Valid @RequestBody UpdateBillingRequest request,
            AuthContext auth) {
        auth.requireOwnerAccess();
        return ResponseEntity.ok(billingOverviewService.updateBillingEmail(request.getBillingEmail()));
    }

    @Operation(summary = "Change organization plan", description = "Directly assigns a plan to the organization (owner only). " +
            "For paid plans, use checkout instead.")
    @RequireAccess(AccessLevel.OWNER)
@PutMapping("/organization/plan")
    public ResponseEntity<OrganizationBillingResponse> changePlan(
            @Valid @RequestBody ChangePlanRequest request,
            AuthContext auth) {
        auth.requireOwnerAccess();
        billingService.assignPlan(request.getPlanName());
        return ResponseEntity.ok(billingOverviewService.organizationBilling());
    }

    // ── Usage ──────────────────────────────────────────────────────

    @Operation(operationId = "getBillingUsage", summary = "Get detailed usage", description = "Returns current resource usage vs plan limits for all quota types")
    @GetMapping("/usage")
    public ResponseEntity<UsageResponse> getUsage(AuthContext auth) {
        auth.requireJwt();
        return ResponseEntity.ok(billingOverviewService.usage());
    }

    // ── Invoices ───────────────────────────────────────────────────

    @Operation(summary = "List invoices", description = "Returns invoice history from the billing provider. " +
            "Empty when billing is disabled (self-hosted).")
    @GetMapping("/invoices")
    public ResponseEntity<List<InvoiceResponse>> listInvoices(AuthContext auth) {
        auth.requireJwt();
        return ResponseEntity.ok(billingService.listInvoices());
    }

    // ── Checkout / Portal ─────────────────────────────────────────

    @Operation(summary = "Create checkout session", description = "Creates a billing provider checkout session for plan upgrade")
    @ApiResponse(responseCode = "200", description = "Checkout URL returned")
    @RequireAccess(AccessLevel.OWNER)
@PostMapping("/checkout")
    public ResponseEntity<Map<String, String>> createCheckout(
            @Valid @RequestBody CheckoutRequest request,
            AuthContext auth) {
        auth.requireOwnerAccess();
        String url = billingService.createCheckoutSession(request.getPlanName(),
                request.getProviderCode(), request.getBillingInterval(),
                request.getSuccessUrl(), request.getCancelUrl());
        return ResponseEntity.ok(Map.of("url", url));
    }

    @Operation(summary = "Create portal session", description = "Creates a billing provider portal session for managing subscription")
    @RequireAccess(AccessLevel.OWNER)
@PostMapping("/portal")
    public ResponseEntity<Map<String, String>> createPortal(
            @RequestParam("returnUrl") String returnUrl,
            AuthContext auth) {
        auth.requireOwnerAccess();
        String url = billingService.createPortalSession(returnUrl);
        return ResponseEntity.ok(Map.of("url", url));
    }

    @Operation(summary = "Cancel subscription", description = "Cancels the current subscription and downgrades to free plan")
    @RequireAccess(AccessLevel.OWNER)
@PostMapping("/cancel")
    public ResponseEntity<Void> cancelSubscription(AuthContext auth) {
        auth.requireOwnerAccess();
        billingService.cancelSubscription();
        return ResponseEntity.noContent().build();
    }

    // ── Webhook (public, no auth — verified by provider signature) ─

    @Operation(summary = "Billing provider webhook",
            description = "Handles callbacks from billing providers. Each provider has its own endpoint. " +
                    "Signature verification is delegated to the BillingProvider adapter.")
    @ApiResponse(responseCode = "200", description = "Event processed")
    @PostMapping("/webhook/{providerCode}")
    public ResponseEntity<Void> handleWebhook(
            @PathVariable String providerCode,
            @RequestBody String rawPayload,
            @RequestHeader Map<String, String> headers) {
        billingService.processWebhook(providerCode, rawPayload, headers);
        return ResponseEntity.ok().build();
    }

}
