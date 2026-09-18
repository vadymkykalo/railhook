package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.entity.PortalSession;
import com.webhook.platform.api.domain.repository.PortalSessionRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

/**
 * Authenticates a portal session's bearer token, on the portal's own routes and nowhere else.
 *
 * <p>Confined to {@code /api/v1/portal/**} by {@link #shouldNotFilter}, so the token cannot even be
 * looked at on a tenant route: there it is just an unrecognised bearer, and the request is
 * anonymous. The session is found by the SHA-256 of the token, under the system scope, exactly as
 * an API key is — the organization is what the lookup is for.
 *
 * <p>An expired session, or one whose project has since been deleted, leaves the request
 * unauthenticated, which {@code SecurityConfig} answers with 401. So does one whose Consumer is
 * gone: deleting a Consumer deletes its sessions with it.
 *
 * <p>Rate-limited per session here rather than per organization: a session is a credential in a
 * browser that is not the customer's, and a script looping over it should spend its own budget,
 * not the budget of the customer's dashboard.
 */
@Slf4j
@Component
public class PortalSessionAuthenticationFilter extends OncePerRequestFilter {

    static final String PORTAL_PATH_PREFIX = "/api/v1/portal/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final PortalSessionRepository portalSessionRepository;
    private final ProjectRepository projectRepository;
    private final RedisRateLimiterService rateLimiterService;
    private final Clock clock;
    private final int requestsPerSecond;

    public PortalSessionAuthenticationFilter(
            PortalSessionRepository portalSessionRepository,
            ProjectRepository projectRepository,
            RedisRateLimiterService rateLimiterService,
            Clock clock,
            @Value("${portal.rate-limit.requests-per-second:20}") int requestsPerSecond) {
        this.portalSessionRepository = portalSessionRepository;
        this.projectRepository = projectRepository;
        this.rateLimiterService = rateLimiterService;
        this.clock = clock;
        this.requestsPerSecond = requestsPerSecond;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PORTAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX + PortalSessionAuthenticationToken.TOKEN_PREFIX)) {
            String tokenHash = CryptoUtils.hashApiKey(header.substring(BEARER_PREFIX.length()));
            Optional<PortalSession> session = TenantContext.callAsSystem(
                    () -> portalSessionRepository.findByTokenHash(tokenHash))
                    .filter(s -> s.getExpiresAt().isAfter(clock.instant()))
                    .filter(s -> TenantContext.callAsSystem(() -> projectRepository.findById(s.getProjectId())).isPresent());

            if (session.isPresent()) {
                PortalSession live = session.get();
                if (!rateLimiterService.tryAcquireForPortalSession(live.getId(), requestsPerSecond)) {
                    log.warn("Portal session {} exceeded its rate limit ({}/sec)", live.getId(), requestsPerSecond);
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setContentType("application/json");
                    response.setHeader("Retry-After", "1");
                    response.getWriter().write("{\"error\":\"portal_rate_limit\","
                            + "\"message\":\"Too many requests. Please retry shortly.\",\"status\":429}");
                    return;
                }
                // Replaces whatever the other filters found: on these routes only a portal
                // session is a caller, so an API key sent alongside the token grants nothing.
                SecurityContextHolder.getContext().setAuthentication(new PortalSessionAuthenticationToken(
                        live.getId(), live.getOrganizationId(), live.getProjectId(), live.getConsumerId()));
                MDC.put("projectId", live.getProjectId().toString());
            }
        }

        filterChain.doFilter(request, response);
    }
}
