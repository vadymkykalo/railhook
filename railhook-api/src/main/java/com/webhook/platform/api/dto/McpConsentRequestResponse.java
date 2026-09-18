package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** What the consent screen shows about an MCP app asking to connect. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpConsentRequestResponse {

    private UUID requestId;

    /** The name the app registered under. Self-declared: the redirect host is what proves anything. */
    private String clientName;

    private String clientUri;

    /** The host the authorization code will be sent to on approval. */
    private String redirectHost;

    /** What the app asked for: READ_WRITE when it asked for mcp:write, READ_ONLY otherwise. */
    private ApiKeyScope requestedScope;

    /** Whether the signed-in person's role may grant READ_WRITE — the roles that may create an API key. */
    private boolean canGrantWrite;

    private Instant expiresAt;
}
