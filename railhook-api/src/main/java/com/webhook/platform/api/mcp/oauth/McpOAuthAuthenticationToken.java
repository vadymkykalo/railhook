package com.webhook.platform.api.mcp.oauth;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;

import java.util.Collections;
import java.util.UUID;

/**
 * A caller on {@code /mcp} holding an OAuth access token.
 *
 * <p>An {@link ApiKeyAuthenticationToken} on purpose. A grant is the same (organization, project,
 * scope) triple a key is, so everything downstream — the tenant filter, the audit aspect,
 * {@code McpCaller}'s READ_ONLY and suspension checks — already knows what to do with it, and
 * none of them grew a second branch that could drift from the first.
 *
 * <p>The credential it carries is the grant's id, never the token: the token is a bearer secret
 * and has no business sitting in a security context.
 */
public class McpOAuthAuthenticationToken extends ApiKeyAuthenticationToken {

    private final UUID grantId;

    public McpOAuthAuthenticationToken(UUID grantId, UUID projectId, UUID organizationId, ApiKeyScope scope) {
        super("oauth-grant:" + grantId, projectId, organizationId, scope, Collections.emptyList());
        this.grantId = grantId;
    }

    public UUID getGrantId() {
        return grantId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof McpOAuthAuthenticationToken token && token.grantId.equals(grantId) && super.equals(other);
    }

    @Override
    public int hashCode() {
        return grantId.hashCode();
    }
}
