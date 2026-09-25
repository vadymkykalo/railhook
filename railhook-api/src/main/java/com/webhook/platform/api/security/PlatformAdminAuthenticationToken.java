package com.webhook.platform.api.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * The cluster operator, not a tenant user: granted only after a constant-time match against
 * {@code platform.admin.token}, never derived from a JWT or API key.
 */
public class PlatformAdminAuthenticationToken extends AbstractAuthenticationToken {

    public static final String AUTHORITY = "PLATFORM_ADMIN";

    /** Held only by the operator token, never by a browser session. */
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
