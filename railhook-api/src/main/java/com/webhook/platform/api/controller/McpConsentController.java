package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.McpConsentApproveRequest;
import com.webhook.platform.api.dto.McpConsentDecisionResponse;
import com.webhook.platform.api.dto.McpConsentRequestResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.McpOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The decision is an API call carrying the JWT in a header, not a form post a foreign page could
 * make a browser send. API keys are refused: a key must never be able to mint another credential.
 */
@RestController
@RequestMapping("/api/v1/oauth/requests")
@Tag(name = "MCP apps", description = "Connecting AI apps to a project over OAuth, for the MCP server")
@SecurityRequirement(name = "bearerAuth")
public class McpConsentController {

    private final McpOAuthService oauthService;

    public McpConsentController(McpOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @Operation(summary = "Describe an MCP app's sign-in request",
            description = "What the consent screen shows: the app's name, the host its code will be sent to, "
                    + "the access it asked for, and whether the caller's role may grant write access.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The request, still waiting for an answer"),
            @ApiResponse(responseCode = "404", description = "Expired, already answered, or never existed"),
    })
    @GetMapping("/{requestId}")
    public ResponseEntity<McpConsentRequestResponse> describeMcpConsentRequest(@PathVariable("requestId") UUID requestId,
                                                              AuthContext auth) {
        auth.requireJwt();
        return ResponseEntity.ok(oauthService.describeRequest(requestId, auth.requireUserId()));
    }

    @Operation(summary = "Approve an MCP app's sign-in request",
            description = "Connects the app to one project of the caller's organization with READ_ONLY or "
                    + "READ_WRITE access — the same scope an API key carries. Any member may grant READ_ONLY; "
                    + "READ_WRITE takes a role that may create an API key. Returns the URL to send the browser "
                    + "to, carrying the authorization code.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved; follow redirectUrl"),
            @ApiResponse(responseCode = "403", description = "The caller's role may not grant READ_WRITE"),
            @ApiResponse(responseCode = "404", description = "Request expired or answered, or project not found"),
    })
    @RequireScope(ApiKeyScope.READ_WRITE)
    // A Viewer may connect an app read-only; the service refuses READ_WRITE to a role that could
    // not create an API key.
    @RequireAccess(AccessLevel.READ)
    @PostMapping("/{requestId}/approve")
    public ResponseEntity<McpConsentDecisionResponse> approveMcpConsentRequest(@PathVariable("requestId") UUID requestId,
                                                              @Valid @RequestBody McpConsentApproveRequest request,
                                                              AuthContext auth) {
        auth.requireJwt();
        return ResponseEntity.ok(oauthService.approve(requestId, auth.requireUserId(), request));
    }

    @Operation(summary = "Decline an MCP app's sign-in request",
            description = "Ends the request and returns the URL that tells the app access was denied.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Declined; follow redirectUrl"),
            @ApiResponse(responseCode = "404", description = "Expired, already answered, or never existed"),
    })
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.READ)
    @PostMapping("/{requestId}/deny")
    public ResponseEntity<McpConsentDecisionResponse> denyMcpConsentRequest(@PathVariable("requestId") UUID requestId,
                                                           AuthContext auth) {
        auth.requireJwt();
        return ResponseEntity.ok(oauthService.deny(requestId));
    }
}
