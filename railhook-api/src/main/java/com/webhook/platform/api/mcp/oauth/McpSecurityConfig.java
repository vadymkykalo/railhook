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
 * The security chain for the MCP server and its authorization server — {@code /mcp},
 * {@code /oauth/*} and the {@code /.well-known} metadata — kept apart from the dashboard's.
 *
 * <p>Three things differ from the main chain, and each is why these paths have a chain of their
 * own rather than exceptions threaded through {@code SecurityConfig}:
 * <ul>
 *   <li><b>The 401.</b> An MCP client that is refused must be told where to sign in:
 *       {@code WWW-Authenticate: Bearer resource_metadata="…"} (RFC 9728 §5.1), with
 *       {@code error="invalid_token"} when it did send a token (RFC 6750 §3.1). The dashboard's
 *       JSON 401 tells it nothing.</li>
 *   <li><b>CORS.</b> Any origin, without credentials. Nothing here is authorized by an ambient
 *       credential — every request carries its token, key or client secret explicitly — so a
 *       foreign page gains nothing it could not do from a server, and browser-based MCP clients
 *       (the MCP Inspector, web agents) need the metadata, registration and token endpoints to
 *       answer them. The dashboard's allowlist would refuse them all.</li>
 *   <li><b>Who may call.</b> Only an OAuth access token or a project API key reaches {@code /mcp};
 *       the protocol endpoints are public by definition, each proving the caller its own way.</li>
 * </ul>
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
                // Last, as in the main chain: it turns whichever identity was established into
                // the tenant scope the request then runs in.
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
