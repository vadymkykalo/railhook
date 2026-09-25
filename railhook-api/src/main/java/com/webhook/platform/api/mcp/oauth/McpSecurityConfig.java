package com.webhook.platform.api.mcp.oauth;

import com.webhook.platform.api.mcp.McpServerConfig;
import com.webhook.platform.api.security.ApiKeyAuthenticationFilter;
import com.webhook.platform.api.service.McpOAuthService;
import com.webhook.platform.api.tenancy.TenantContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * A separate chain from the dashboard's because the 401 must carry a
 * {@code WWW-Authenticate: Bearer resource_metadata=...} challenge (RFC 9728), and CORS is open
 * to any origin without credentials. That is safe because nothing here is authorized by an
 * ambient credential, and browser-based MCP clients need these endpoints to answer them.
 */
@Configuration(proxyBeanMethods = false)
public class McpSecurityConfig {

    static final String[] PATHS = {
            McpServerConfig.ENDPOINT,
            "/oauth/register", "/oauth/authorize", "/oauth/token", "/oauth/revoke",
            "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**",
            "/.well-known/oauth-authorization-server", "/.well-known/oauth-authorization-server/**",
    };

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
                                                      McpOAuthService oauthService,
                                                      McpOAuthSettings settings,
                                                      ApiKeyAuthenticationFilter apiKeyAuthenticationFilter) throws Exception {
        McpAccessTokenFilter accessTokenFilter = new McpAccessTokenFilter(oauthService);
        http
                .securityMatcher(PATHS)
                .cors(cors -> cors.configurationSource(corsConfiguration()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(McpServerConfig.ENDPOINT).authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, e) -> challenge(request, response, settings)))
                .addFilterBefore(accessTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyAuthenticationFilter, McpAccessTokenFilter.class)
                // Last: turns the established identity into the tenant scope.
                .addFilterAfter(new TenantContextFilter(), ApiKeyAuthenticationFilter.class);
        return http.build();
    }

    private static void challenge(HttpServletRequest request, HttpServletResponse response, McpOAuthSettings settings)
            throws IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean sentCredentials = (authorization != null && !authorization.isBlank())
                || request.getHeader("X-API-Key") != null;
        StringBuilder header = new StringBuilder("Bearer ");
        if (sentCredentials) {
            header.append("error=\"invalid_token\", error_description=\"The access token or API key is not valid\"");
        }
        if (settings.enabled()) {
            if (sentCredentials) {
                header.append(", ");
            }
            header.append("resource_metadata=\"").append(settings.resourceMetadataUrl()).append('"');
        } else if (!sentCredentials) {
            header.append("realm=\"railhook\"");
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, header.toString());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Authentication required: sign in "
                + "with OAuth or send a project API key as 'Authorization: Bearer <key>'\",\"status\":401}");
    }

    private static UrlBasedCorsConfigurationSource corsConfiguration() {
        CorsConfiguration open = new CorsConfiguration();
        open.setAllowedOriginPatterns(List.of("*"));
        open.setAllowCredentials(false);
        open.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        open.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-API-Key",
                "Mcp-Session-Id", "MCP-Protocol-Version", "Last-Event-ID"));
        open.setExposedHeaders(List.of("WWW-Authenticate", "Mcp-Session-Id"));
        open.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", open);
        return source;
    }
}
