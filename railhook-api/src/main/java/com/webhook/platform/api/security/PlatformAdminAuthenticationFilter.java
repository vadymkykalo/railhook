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
 * A single shared operator secret, unrelated to any tenant credential, so no tenant's JWT or API
 * key can satisfy it. Fails closed: with the secret unset, nobody is authenticated.
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
