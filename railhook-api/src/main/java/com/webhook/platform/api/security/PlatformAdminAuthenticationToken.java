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

    public static final String AUTHORITY = "PLATFORM_ADMIN";

    public PlatformAdminAuthenticationToken() {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
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
