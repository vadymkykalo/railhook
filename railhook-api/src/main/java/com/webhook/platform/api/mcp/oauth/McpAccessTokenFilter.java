package com.webhook.platform.api.mcp.oauth;

import com.webhook.platform.api.mcp.McpServerConfig;
import com.webhook.platform.api.service.McpOAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Recognises access tokens by prefix, so an API key sent as a bearer passes through to the API key
 * filter. Not a {@code @Component}: a filter bean would also be registered with the servlet
 * container and run outside the security chain.
 */
public class McpAccessTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final McpOAuthService oauthService;

    public McpAccessTokenFilter(McpOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !McpServerConfig.ENDPOINT.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            String token = authorization.substring(BEARER.length()).trim();
            if (token.startsWith(OAuthSecrets.ACCESS_TOKEN_PREFIX)) {
                oauthService.authenticate(token).ifPresent(caller -> {
                    SecurityContextHolder.getContext().setAuthentication(caller);
                    MDC.put("projectId", caller.getProjectId().toString());
                });
            }
        }
        chain.doFilter(request, response);
    }
}
