package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.MembershipRole;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID userId;
    private final UUID organizationId;
    private final MembershipRole role;
    // Carried on the token to avoid a user lookup on every write.
    private final boolean emailVerified;
    private final boolean demo;

    public JwtAuthenticationToken(
            UUID userId,
            UUID organizationId,
            MembershipRole role,
            boolean emailVerified,
            Collection<? extends GrantedAuthority> authorities) {
        this(userId, organizationId, role, emailVerified, false, authorities);
    }

    public JwtAuthenticationToken(
            UUID userId,
            UUID organizationId,
            MembershipRole role,
            boolean emailVerified,
            boolean demo,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userId = userId;
        this.organizationId = organizationId;
        this.role = role;
        this.emailVerified = emailVerified;
        this.demo = demo;
        setAuthenticated(true);
    }

    public boolean isDemo() {
        return demo;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public MembershipRole getRole() {
        return role;
    }
}
