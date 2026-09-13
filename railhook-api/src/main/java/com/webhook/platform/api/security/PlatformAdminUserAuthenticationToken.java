package com.webhook.platform.api.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * A signed-in person acting as the platform admin, on {@code /api/v1/admin/**} only.
 *
 * <p>Minted by {@link PlatformAdminAccessFilter} from the request's own
 * {@link JwtAuthenticationToken}, after the checks that make a listed address mean something —
 * never by the JWT filter and never outside the admin paths, so the authority does not ride
 * along on the tenant requests the same token makes.
 *
 * <p>Still a {@code JwtAuthenticationToken}, so the audit trail records the real user id. It
 * carries {@link PlatformAdminAuthenticationToken#AUTHORITY} but not
 * {@link PlatformAdminAuthenticationToken#OPERATOR_TOKEN_AUTHORITY}: what only the deployment's
 * operator token may do (re-encrypting every tenant's secrets) stays with that token.
 */
public class PlatformAdminUserAuthenticationToken extends JwtAuthenticationToken {

    private final String email;

    public PlatformAdminUserAuthenticationToken(JwtAuthenticationToken signedIn, String email) {
        super(signedIn.getUserId(), signedIn.getOrganizationId(), signedIn.getRole(), signedIn.isEmailVerified(),
                List.of(new SimpleGrantedAuthority(PlatformAdminAuthenticationToken.AUTHORITY)));
        this.email = email;
    }

    /** The verified address the list matched — what a suspension is signed with. */
    public String getEmail() {
        return email;
    }
}
