package com.webhook.platform.api.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authentication for the platform-admin operator credential.
 *
 * <p>Deliberately independent of {@link MembershipRole} / organization membership: this
 * represents a cluster operator, not a tenant user. It is granted only by
 * {@link PlatformAdminAuthenticationFilter} after a constant-time match against the
 * {@code platform.admin.token} secret — never derived from a JWT or API key.
 */
public class PlatformAdminAuthenticationToken extends AbstractAuthenticationToken {

    /** The platform admin panel — held by this token and by a verified, listed, recent sign-in. */
    public static final String AUTHORITY = "PLATFORM_ADMIN";

    /**
     * What only the deployment's operator token may do: re-encrypting every tenant's secrets is
     * run from the deployment, and no browser session — however verified — carries it.
     */
    public static final String OPERATOR_TOKEN_AUTHORITY = "PLATFORM_ADMIN_TOKEN";

    public PlatformAdminAuthenticationToken() {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY), new SimpleGrantedAuthority(OPERATOR_TOKEN_AUTHORITY)));
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return "platform-admin";
    }
}
