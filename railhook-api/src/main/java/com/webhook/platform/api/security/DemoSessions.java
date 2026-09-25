package com.webhook.platform.api.security;

import com.webhook.platform.common.demo.DemoTenant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A caller is in the demo if its token carries the demo claim, or if any credential belongs to
 * the demo organization. The second check covers ways into that organization nobody anticipated.
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

    public static boolean isCurrent() {
        return isDemo(SecurityContextHolder.getContext().getAuthentication());
    }
}
