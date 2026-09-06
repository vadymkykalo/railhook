package com.webhook.platform.api.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import com.webhook.platform.api.security.JwtAuthenticationToken;
import com.webhook.platform.api.security.PlatformAdminAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns the authenticated identity into the tenant scope the rest of the request runs in.
 *
 * <p>Runs after the three authentication filters and before anything that touches the database,
 * so a handler, a service and a repository all see the same organization without any of them
 * being handed one. This is the request half of making org ownership a
 * property of data access; {@link TenantContext#runAsSystem} is the other half.
 *
 * <p>A JWT maps to the organization in the token, an API key to the one owning its project, and
 * a platform admin to {@link TenantContext#SYSTEM} — not being a member of any organization, and
 * meant to see across them. An unauthenticated request gets nothing: the public paths have a
 * tenant but no caller identity, so leaving the scope unset is what forces them to discover their
 * own organization and enter it with {@link TenantContext#runAs}.
 *
 * <p>The previous scope is restored in a {@code finally}, which on a real request means clearing
 * it: a leaked {@code ThreadLocal} would hand one request's tenant to the next request on that
 * pooled thread. Restoring rather than clearing matters under MockMvc, which runs the chain on
 * the calling thread.
 *
 * <p>Not a {@code @Component}: a {@code Filter} bean is also registered with the servlet container,
 * where it would run before the security chain with an empty {@code SecurityContext} and then,
 * being a {@code OncePerRequestFilter}, decline to run where the identity exists.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        java.util.UUID previous = TenantContext.current();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            TenantContext.set(jwt.getOrganizationId());
        } else if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            TenantContext.set(apiKey.getOrganizationId());
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
