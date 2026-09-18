package com.webhook.platform.api.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

/**
 * A request made from the customer portal, on behalf of one Consumer.
 *
 * <p>Carries no membership role and no API-key scope, so every tenant handler that asks for an
 * {@code AuthContext} refuses it; its one authority is what {@code SecurityConfig} admits on
 * {@code /api/v1/portal/**}, and nowhere else.
 */
public class PortalSessionAuthenticationToken extends AbstractAuthenticationToken {

    /** The only authority a portal session holds, and the only one the portal routes accept. */
    public static final String AUTHORITY = "PORTAL_SESSION";

    /** Every portal token starts with this, so it is recognisable in a log or a leaked-secret scan. */
    public static final String TOKEN_PREFIX = "rhp_";

    private final UUID sessionId;
    private final UUID organizationId;
    private final UUID projectId;
    private final UUID consumerId;

    public PortalSessionAuthenticationToken(UUID sessionId, UUID organizationId, UUID projectId, UUID consumerId) {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        this.sessionId = sessionId;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.consumerId = consumerId;
        setAuthenticated(true);
    }

    /** Never the token: nothing downstream needs it, and a credential kept is a credential logged. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return consumerId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getConsumerId() {
        return consumerId;
    }

    public PortalContext toContext() {
        return new PortalContext(sessionId, projectId, consumerId);
    }
}
