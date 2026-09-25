package com.webhook.platform.api.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import com.webhook.platform.api.security.JwtAuthenticationToken;
import com.webhook.platform.api.security.PlatformAdminAuthenticationToken;
import com.webhook.platform.api.security.PlatformAdminUserAuthenticationToken;
import com.webhook.platform.api.security.PortalSessionAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sets the tenant scope for the request from the authenticated identity. An unauthenticated
 * request gets no scope, so public paths must find their organization and enter it with
 * {@link TenantContext#runAs}.
 *
 * <p>The previous scope is restored rather than cleared because MockMvc runs the chain on the
 * calling thread.
 *
 * <p>Not a {@code @Component}: a {@code Filter} bean would also be registered with the servlet
 * container, run there before security with no identity, and then, being a
 * {@code OncePerRequestFilter}, skip the run where the identity exists.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        java.util.UUID previous = TenantContext.current();
        if (authentication instanceof PlatformAdminUserAuthenticationToken) {
            // Must precede the JWT branch, which it extends.
            TenantContext.set(TenantContext.SYSTEM);
        } else if (authentication instanceof JwtAuthenticationToken jwt) {
            TenantContext.set(jwt.getOrganizationId());
        } else if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            TenantContext.set(apiKey.getOrganizationId());
        } else if (authentication instanceof PortalSessionAuthenticationToken portal) {
            TenantContext.set(portal.getOrganizationId());
        } else if (authentication instanceof PlatformAdminAuthenticationToken) {
            TenantContext.set(TenantContext.SYSTEM);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.restore(previous);
        }
    }
}
