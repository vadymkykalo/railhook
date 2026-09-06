package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String apiKey;
    private final UUID projectId;
    private final UUID organizationId;
    private final ApiKeyScope scope;

    public ApiKeyAuthenticationToken(String apiKey) {
        // Cast because Spring Security 7 added a second, builder-taking constructor, which
        // makes a bare null ambiguous. Still the authorities overload, still "none yet".
        super((Collection<? extends GrantedAuthority>) null);
        this.apiKey = apiKey;
        this.projectId = null;
        this.organizationId = null;
        this.scope = null;
        setAuthenticated(false);
    }

    public ApiKeyAuthenticationToken(String apiKey, UUID projectId, UUID organizationId, ApiKeyScope scope,
                                     Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.apiKey = apiKey;
        this.projectId = projectId;
        this.organizationId = organizationId;
        this.scope = scope;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return apiKey;
    }

    @Override
    public Object getPrincipal() {
        return projectId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    /**
     * The organization that owns the key's project.
     *
     * <p>Resolved once, in {@code ApiKeyAuthenticationFilter}, rather than on each use: an API
     * key names a project and the tenant is one join away, and both {@code TenantContextFilter}
     * and {@code AuthContextArgumentResolver} need the answer. Carrying it on the token replaces
     * the per-request lookup the argument resolver used to do.
     */
    public UUID getOrganizationId() {
        return organizationId;
    }

    public ApiKeyScope getScope() {
        return scope;
    }
}
