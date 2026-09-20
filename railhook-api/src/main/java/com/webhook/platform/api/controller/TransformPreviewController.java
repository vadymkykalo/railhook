package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.DeliveryDryRunRequest;
import com.webhook.platform.api.dto.DeliveryDryRunResponse;
import com.webhook.platform.api.dto.TransformPreviewRequest;
import com.webhook.platform.api.dto.TransformPreviewResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AllowedInDemo;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.DeliveryDryRunService;
import com.webhook.platform.api.service.TransformPreviewService;
import com.webhook.platform.api.service.demo.DemoDryRunMask;
import com.webhook.platform.api.service.demo.DemoScriptBudget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/transform-preview")
@Tag(name = "Transform Preview", description = "Preview payload transformations and dry-run deliveries")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class TransformPreviewController {

    private final TransformPreviewService transformPreviewService;
    private final DeliveryDryRunService deliveryDryRunService;
    private final DemoScriptBudget demoScriptBudget;

    @Operation(summary = "Preview transform", description = "Test a payload transform expression against sample input")
    @AllowedInDemo(reason = "runs a script and returns what it produced; writes nothing, and the "
            + "Transform Studio is the demo's one screen that is worth nothing without a Run button")
    @PostMapping
    public ResponseEntity<TransformPreviewResponse> preview(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody TransformPreviewRequest request,
            AuthContext auth,
            HttpServletRequest http) {
        auth.validateProjectAccess(projectId);
        demoScriptBudget.spendIfDemo(http);
        return ResponseEntity.ok(transformPreviewService.preview(projectId, request));
    }

    // Unlike /transform-preview above, which only ever renders a template against
    // caller-supplied sample input, this returns a real X-Signature computed with the endpoint's signing secret.
    // That is a write-level capability: holding it lets the caller mint a payload the
    // destination will accept as genuine. Guard it like one.
    //
    // The public demo is the one caller that gets past that guard, and only because it is handed
    // a dry-run without the capability in it: DemoDryRunMask replaces the signature before the
    // answer leaves this method, so a visitor sees the body, the URL and every header the real
    // attempt would send, and nothing anybody could present to a receiver. A demo session is a
    // VIEWER, so @RequireAccess(WRITE) would otherwise refuse it outright — see
    // ScopeEnforcementInterceptor.enforceAccessLevel for why @AllowedInDemo lifts the level here
    // and nowhere a level is the only protection.
    @Operation(summary = "Dry-run delivery", description = "Simulate a full delivery: transform payload, compute HMAC signature, build headers — without actually sending the request")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @AllowedInDemo(reason = "changes nothing, and the demo's copy has no usable signature in it "
            + "(DemoDryRunMask); without it the Studio cannot show what a real Delivery would carry")
    @PostMapping("/delivery-dry-run")
    public ResponseEntity<DeliveryDryRunResponse> deliveryDryRun(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody DeliveryDryRunRequest request,
            AuthContext auth,
            HttpServletRequest http) {
        boolean demo = demoScriptBudget.spendIfDemo(http);
        if (!demo) {
            auth.requireWriteAccess();
        }
        auth.validateProjectAccess(projectId);
        DeliveryDryRunResponse result = deliveryDryRunService.dryRun(projectId, request);
        return ResponseEntity.ok(demo ? DemoDryRunMask.withoutSignature(result) : result);
    }
}
