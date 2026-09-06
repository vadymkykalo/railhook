package com.webhook.platform.api.config;

import com.webhook.platform.api.security.ApiKeyAuthenticationFilter;
import com.webhook.platform.api.security.JwtAuthenticationFilter;
import com.webhook.platform.api.security.PlatformAdminAuthenticationFilter;
import com.webhook.platform.api.security.PlatformAdminAuthenticationToken;
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
        private final TenantContextFilter tenantContextFilter = new TenantContextFilter();
        private final CorsConfigurationSource corsConfigurationSource;
        private final boolean swaggerEnabled;
        private final Environment environment;

        public SecurityConfig(
                        ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                        JwtAuthenticationFilter jwtAuthenticationFilter,
                        PlatformAdminAuthenticationFilter platformAdminAuthenticationFilter,
                        @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource,
                        @Value("${swagger.enabled:false}") boolean swaggerEnabled,
                        Environment environment) {
                this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
                this.jwtAuthenticationFilter = jwtAuthenticationFilter;
                this.platformAdminAuthenticationFilter = platformAdminAuthenticationFilter;
                this.corsConfigurationSource = corsConfigurationSource;
                this.swaggerEnabled = swaggerEnabled;
                this.environment = environment;
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                http
                                .cors(cors -> cors.configurationSource(corsConfigurationSource))
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
                                                        // These matchers only apply when actuator is served
                                                        // from THIS filter chain, i.e. management.server.port is
                                                        // unset or equal to server.port (the default — true for
                                                        // tests, plain `mvn spring-boot:run`, and any deployment
                                                        // that hasn't opted into the port split). The Compose
                                                        // deployment sets MANAGEMENT_PORT to a separate port so
                                                        // Prometheus can reach /actuator/prometheus without a
                                                        // JWT/API-key — see application.yml `management.server.*`
                                                        // and monitoring/README.md "Metrics-scrape auth". The Helm
                                                        // chart splits the port the same way and its ServiceMonitor
                                                        // scrapes the management port by name; before it did, every
                                                        // scrape in Kubernetes got a 401 and the alert rules fired
                                                        // on no data.
                                                        // Everything arriving on the actuator's own port, when
                                                        // one is configured. That port is never published to the
                                                        // host — the Compose deployment exposes only nginx, and
                                                        // nginx proxies health from it and nothing else — so it
                                                        // is reachable exactly by things already inside the
                                                        // network, which is what "Prometheus scrapes without a
                                                        // JWT" requires. See ManagementPortRequestMatcher for why
                                                        // this is not automatic: Boot copies this filter chain
                                                        // into the management child context, so without it
                                                        // /actuator/prometheus answers 401 on the one port that
                                                        // exists for it to answer on.
                                                        .requestMatchers(new ManagementPortRequestMatcher(environment))
                                                        .permitAll()
                                                        // On the main port — the published one — only the probes
                                                        // are anonymous. Metrics are not: they leak endpoint
                                                        // names, tenant cardinality and traffic volume.
                                                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                                                        "/actuator/info")
                                                        .permitAll()
                                                        .requestMatchers("/actuator/**").authenticated()
                                                        .requestMatchers("/hook/**").permitAll()
                                                        .requestMatchers("/ingress/**").permitAll()
                                                        .requestMatchers("/tunnel/**").permitAll()
                                                        .requestMatchers("/ws/tunnel").permitAll()
                                                        .requestMatchers("/api/v1/public/**").permitAll()
                                                        .requestMatchers("/api/v1/billing/plans").permitAll()
                                                        .requestMatchers("/api/v1/billing/webhook/**").permitAll()
                                                        // Cluster-operator routes — gated on the
                                                        // PLATFORM_ADMIN authority granted only by
                                                        // PlatformAdminAuthenticationFilter, never by tenant
                                                        // JWT/API-key role (org OWNER is not platform admin).
                                                        .requestMatchers("/api/v1/admin/**")
                                                                        .hasAuthority(PlatformAdminAuthenticationToken.AUTHORITY)
                                                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                                                        "/api/v1/auth/refresh",
                                                                        "/api/v1/auth/verify-email",
                                                                        "/api/v1/auth/resend-verification",
                                                                        "/api/v1/auth/forgot-password",
                                                                        "/api/v1/auth/reset-password",
                                                                        "/api/v1/auth/device/code",
                                                                        "/api/v1/auth/device/token")
                                                        .permitAll()
                                                        .requestMatchers("/api/v1/auth/**").authenticated()
                                                        .requestMatchers("/api/v1/orgs/**").authenticated()
                                                        .requestMatchers("/api/v1/events").authenticated()
                                                        .requestMatchers("/api/v1/projects/**").authenticated()
                                                        .requestMatchers("/api/v1/deliveries/**").authenticated();

                                        // Swagger access only when explicitly enabled
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
                                // Last of the four on purpose: it reads the identity the other
                                // three establish and turns it into the tenant scope every query
                                // in the request then runs under.
                                .addFilterAfter(tenantContextFilter, PlatformAdminAuthenticationFilter.class);

                return http.build();
        }
}
