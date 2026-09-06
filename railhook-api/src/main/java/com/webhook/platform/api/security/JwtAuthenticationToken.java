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
    /**
     * Whether the account behind this token has proved it owns its address. Carried on the
     * token rather than looked up per request: it changes once, and the alternative is a user
     * row read on the hot path of every write. A token minted before the claim existed reads
     * as verified — the same answer a self-hosted deployment gives, and the one that does not
     * lock existing sessions out on upgrade.
     */
    private final boolean emailVerified;

    public JwtAuthenticationToken(
            UUID userId,
            UUID organizationId,
            MembershipRole role,
            boolean emailVerified,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userId = userId;
        this.organizationId = organizationId;
        this.role = role;
        this.emailVerified = emailVerified;
        setAuthenticated(true);
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
