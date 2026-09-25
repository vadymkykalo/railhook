package com.webhook.platform.api.config;

import com.webhook.platform.api.security.ApiKeyAuthenticationFilter;
import com.webhook.platform.api.security.JwtAuthenticationFilter;
import com.webhook.platform.api.security.PlatformAdminAuthenticationFilter;
import com.webhook.platform.api.security.PlatformAdminAuthenticationToken;
import com.webhook.platform.api.audit.AuditLogAspect;
import com.webhook.platform.api.security.JwtUtil;
import com.webhook.platform.api.security.PlatformAdminAccessFilter;
import com.webhook.platform.api.security.PortalSessionAuthenticationFilter;
import com.webhook.platform.api.security.PortalSessionAuthenticationToken;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.PlatformAdminAccessService;
import com.webhook.platform.api.tenancy.TenantContextFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

        private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
        private final JwtAuthenticationFilter jwtAuthenticationFilter;
        private final PlatformAdminAuthenticationFilter platformAdminAuthenticationFilter;
        private final PlatformAdminAccessFilter platformAdminAccessFilter;
        private final PortalSessionAuthenticationFilter portalSessionAuthenticationFilter;
        private final TenantContextFilter tenantContextFilter = new TenantContextFilter();
        private final CorsConfigurationSource corsConfigurationSource;
        private final boolean swaggerEnabled;
        private final Environment environment;

        public SecurityConfig(
                        ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                        JwtAuthenticationFilter jwtAuthenticationFilter,
                        PlatformAdminAuthenticationFilter platformAdminAuthenticationFilter,
                        PortalSessionAuthenticationFilter portalSessionAuthenticationFilter,
                        PlatformAdminAccessService platformAdminAccessService,
                        JwtUtil jwtUtil,
                        AuthRateLimiterService authRateLimiterService,
                        AuditLogAspect auditLogAspect,
                        TrustedProxyResolver trustedProxyResolver,
                        @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource,
                        @Value("${swagger.enabled:false}") boolean swaggerEnabled,
                        Environment environment) {
                this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
                this.jwtAuthenticationFilter = jwtAuthenticationFilter;
                this.platformAdminAuthenticationFilter = platformAdminAuthenticationFilter;
                this.portalSessionAuthenticationFilter = portalSessionAuthenticationFilter;
                this.platformAdminAccessFilter = new PlatformAdminAccessFilter(platformAdminAccessService, jwtUtil,
                                authRateLimiterService, auditLogAspect, trustedProxyResolver);
                this.corsConfigurationSource = corsConfigurationSource;
                this.swaggerEnabled = swaggerEnabled;
                this.environment = environment;
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                http
                                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                                // JWTs and API keys arrive in headers. The one cookie, refresh_token,
                                // is SameSite, scoped to /api/v1/auth, and only mints a token into a
                                // response CORS keeps a foreign origin from reading.
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .headers(headers -> headers
                                                .contentSecurityPolicy(csp -> csp
                                                                .policyDirectives(
                                                                                "default-src 'self'; frame-ancestors 'none'; form-action 'self'"))
                                                .xssProtection(xss -> xss
                                                                .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                                                .frameOptions(frame -> frame.deny()))
                                .authorizeHttpRequests(auth -> {
                                        auth
                                                        // The separate management port is never published, so
                                                        // Prometheus scrapes it without credentials. Boot copies
                                                        // this chain into the management context, so without
                                                        // this matcher that port would answer 401 too.
                                                        .requestMatchers(new ManagementPortRequestMatcher(environment))
                                                        .permitAll()
                                                        // On the published port only the probes are anonymous:
                                                        // metrics leak endpoint names and traffic volume.
                                                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                                                        "/actuator/info")
                                                        .permitAll()
                                                        .requestMatchers("/actuator/**").authenticated()
                                                        .requestMatchers("/hook/**").permitAll()
                                                        .requestMatchers("/ingress/**").permitAll()
                                                        .requestMatchers("/tunnel/**").permitAll()
                                                        .requestMatchers("/ws/tunnel").permitAll()
                                                        .requestMatchers("/api/v1/public/**").permitAll()
                                                        // Portal session only. A portal token anywhere else
                                                        // is anonymous.
                                                        .requestMatchers("/api/v1/portal/**")
                                                                        .hasAuthority(PortalSessionAuthenticationToken.AUTHORITY)
                                                        .requestMatchers("/api/v1/billing/plans").permitAll()
                                                        .requestMatchers("/api/v1/billing/webhook/**").permitAll()
                                                        // Re-encrypts every tenant's secrets: operator token
                                                        // only, never a signed-in platform admin.
                                                        .requestMatchers("/api/v1/admin/encryption/**")
                                                                        .hasAuthority(PlatformAdminAuthenticationToken.OPERATOR_TOKEN_AUTHORITY)
                                                        // An organization OWNER is not a platform admin.
                                                        .requestMatchers("/api/v1/admin/**")
                                                                        .hasAuthority(PlatformAdminAuthenticationToken.AUTHORITY)
                                                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                                                        "/api/v1/auth/refresh",
                                                                        "/api/v1/auth/verify-email",
                                                                        "/api/v1/auth/resend-verification",
                                                                        // Opened from mail, possibly with no session.
                                                                        "/api/v1/auth/email-change/confirm",
                                                                        "/api/v1/auth/email-change/cancel",
                                                                        "/api/v1/auth/forgot-password",
                                                                        "/api/v1/auth/reset-password",
                                                                        "/api/v1/auth/device/code",
                                                                        "/api/v1/auth/device/token",
                                                                        "/api/v1/auth/providers",
                                                                        "/api/v1/auth/oauth/google/start",
                                                                        "/api/v1/auth/oauth/google/callback",
                                                                        "/api/v1/auth/oauth/exchange")
                                                        .permitAll()
                                                        .requestMatchers("/api/v1/auth/**").authenticated()
                                                        .requestMatchers("/api/v1/orgs/**").authenticated()
                                                        .requestMatchers("/api/v1/events").authenticated()
                                                        .requestMatchers("/api/v1/projects/**").authenticated()
                                                        .requestMatchers("/api/v1/deliveries/**").authenticated();

                                        if (swaggerEnabled) {
                                                auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                                                "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll();
                                        }

                                        auth.anyRequest().authenticated();
                                })
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint((request, response, authException) -> {
                                                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                                        response.setContentType("application/json");
                                                        response.getWriter().write(
                                                                        "{\"error\":\"unauthorized\",\"message\":\"Authentication required\",\"status\":401}");
                                                })
                                                .accessDeniedHandler((request, response, accessDeniedException) -> {
                                                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                                        response.setContentType("application/json");
                                                        response.getWriter().write(
                                                                        "{\"error\":\"forbidden\",\"message\":\"Access denied\",\"status\":403}");
                                                }))
                                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                                .addFilterBefore(apiKeyAuthenticationFilter,
                                                UsernamePasswordAuthenticationFilter.class)
                                .addFilterBefore(platformAdminAuthenticationFilter,
                                                UsernamePasswordAuthenticationFilter.class)
                                .addFilterBefore(portalSessionAuthenticationFilter,
                                                UsernamePasswordAuthenticationFilter.class)
                                // Needs both the JWT and the operator token identity already resolved.
                                .addFilterAfter(platformAdminAccessFilter, PlatformAdminAuthenticationFilter.class)
                                // Last: turns the established identity into the tenant scope.
                                .addFilterAfter(tenantContextFilter, PlatformAdminAccessFilter.class);

                return http.build();
        }
}
