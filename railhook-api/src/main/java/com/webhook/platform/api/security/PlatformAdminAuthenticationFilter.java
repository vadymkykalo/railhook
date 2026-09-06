package com.webhook.platform.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates the platform-admin operator credential.
 *
 * <p>This is a cluster-operator credential, not a tenant credential: it is a single shared
 * secret ({@code platform.admin.token}, env {@code PLATFORM_ADMIN_TOKEN}) presented via the
 * {@code X-Platform-Admin-Token} header, checked with a constant-time comparison. It never
 * touches {@link MembershipRole} or an organization id, so it cannot be satisfied by any
 * tenant's JWT or API key — including a tenant that happens to be OWNER of its own org.
 *
 * <p>Fails closed: if the secret is not configured (blank), this filter never authenticates
 * anyone, so admin-only routes stay unreachable until an operator explicitly sets it.
 */
@Slf4j
@Component
public class PlatformAdminAuthenticationFilter extends OncePerRequestFilter {

    private static final String ADMIN_TOKEN_HEADER = "X-Platform-Admin-Token";

    private final String configuredToken;

    public PlatformAdminAuthenticationFilter(@Value("${platform.admin.token:}") String configuredToken) {
        this.configuredToken = configuredToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String presented = request.getHeader(ADMIN_TOKEN_HEADER);

        if (configuredToken != null && !configuredToken.isBlank()
                && presented != null && !presented.isBlank()
                && constantTimeEquals(configuredToken, presented)) {
            SecurityContextHolder.getContext().setAuthentication(new PlatformAdminAuthenticationToken());
        } else if (presented != null && !presented.isBlank()) {
            log.warn("Rejected invalid platform-admin token from {}", request.getRemoteAddr());
        }

        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
