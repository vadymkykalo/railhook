package com.webhook.platform.api.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

/**
 * Carries no membership role or API-key scope, so tenant handlers taking an AuthContext refuse
 * it. Its one authority is admitted only on /api/v1/portal/**.
 */
public class PortalSessionAuthenticationToken extends AbstractAuthenticationToken {

    public static final String AUTHORITY = "PORTAL_SESSION";

    /** Makes a portal token recognisable in logs and leaked-secret scans. */
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

    /** Never the token: nothing downstream needs it, and a kept credential ends up logged. */
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
