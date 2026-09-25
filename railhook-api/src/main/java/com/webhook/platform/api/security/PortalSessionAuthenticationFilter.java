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
 * Only runs on portal routes, so a portal token is an unrecognised bearer anywhere else. The
 * lookup is under the system scope because the organization is what it finds.
 *
 * <p>Rate-limited per session, not per organization: the session lives in a browser that is not
 * the customer's, and a script abusing it should not spend the customer's own budget.
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
                // Replaces any other authentication: an API key sent alongside grants nothing here.
                SecurityContextHolder.getContext().setAuthentication(new PortalSessionAuthenticationToken(
                        live.getId(), live.getOrganizationId(), live.getProjectId(), live.getConsumerId()));
                MDC.put("projectId", live.getProjectId().toString());
            }
        }

        filterChain.doFilter(request, response);
    }
}
