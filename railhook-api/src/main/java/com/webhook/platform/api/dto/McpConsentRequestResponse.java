package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpConsentRequestResponse {

    private UUID requestId;

    /** Self-declared: only the redirect host proves anything. */
    private String clientName;

    private String clientUri;

    private String redirectHost;

    /** READ_WRITE when the app asked for mcp:write, READ_ONLY otherwise. */
    private ApiKeyScope requestedScope;

    /** True for the roles that may create an API key. */
    private boolean canGrantWrite;

    private Instant expiresAt;
}
