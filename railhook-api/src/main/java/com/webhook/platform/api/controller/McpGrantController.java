package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.McpGrantResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.McpOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The MCP apps connected to a project over OAuth, listed and revoked next to its API keys — they
 * are the same kind of credential, and managed by the same people.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/mcp-grants")
@Tag(name = "MCP apps", description = "Connecting AI apps to a project over OAuth, for the MCP server")
@SecurityRequirement(name = "bearerAuth")
public class McpGrantController {

    private final McpOAuthService oauthService;

    public McpGrantController(McpOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @Operation(summary = "List connected MCP apps",
            description = "Apps connected to this project through OAuth sign-in, newest first.")
    @GetMapping
    public ResponseEntity<List<McpGrantResponse>> listMcpGrants(@PathVariable("projectId") UUID projectId,
                                                                AuthContext auth) {
        auth.requireJwt();
        return ResponseEntity.ok(oauthService.listGrants(projectId));
    }

    @Operation(summary = "Disconnect an MCP app",
            description = "Revokes the app's access to this project at once: its access and refresh tokens "
                    + "stop working, and it has to be connected again from the app.")
    @ApiResponse(responseCode = "204", description = "Disconnected")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @DeleteMapping("/{grantId}")
    public ResponseEntity<Void> revokeMcpGrant(@PathVariable("projectId") UUID projectId,
                                               @PathVariable("grantId") UUID grantId,
                                               AuthContext auth) {
        // As for API keys: managing credentials takes a signed-in person.
        auth.requireJwt();
        auth.requireWriteAccess();
        oauthService.revokeGrant(projectId, grantId, auth.requireUserId());
        return ResponseEntity.noContent().build();
    }
}
