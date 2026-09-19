package com.webhook.platform.api.security;

import com.webhook.platform.common.demo.DemoTenant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Recognises a caller acting inside the public demo, which may look at everything and change
 * nothing.
 *
 * <p>Two ways to be one, and either is enough. A token minted by the demo session endpoint
 * carries the {@link JwtUtil#CLAIM_DEMO} claim; and <em>any</em> credential whose organization is
 * {@link DemoTenant#ORGANIZATION_ID} — a JWT, an API key, a portal session — counts too, whatever
 * minted it. The second half is not expected to match anything the first does not: the demo
 * organization has one member, a Viewer, no API keys and no Consumers. It is there so that a way
 * into that organization nobody thought of is still read-only, rather than read-only only for the
 * way everybody thought of.
 */
public final class DemoSessions {

    private DemoSessions() {
    }

    public static boolean isDemo(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.isDemo() || DemoTenant.isDemoOrganization(jwt.getOrganizationId());
        }
        if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            return DemoTenant.isDemoOrganization(apiKey.getOrganizationId());
        }
        if (authentication instanceof PortalSessionAuthenticationToken portal) {
            return DemoTenant.isDemoOrganization(portal.getOrganizationId());
        }
        return false;
    }

    /** Whether the request on this thread comes from the demo. */
    public static boolean isCurrent() {
        return isDemo(SecurityContextHolder.getContext().getAuthentication());
    }
}
