package com.webhook.platform.api.security;

import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.UUID;

/**
 * Bounds what one organization can ask of the control-plane API.
 *
 * <p>{@code GlobalRateLimitFilter} holds a single bucket for the entire platform. One tenant
 * looping over their deliveries can spend it, and every other tenant then gets 429s for
 * something they did not do — a noisy neighbour with no wall between the flats. Event ingestion
 * was never exposed to this ({@code RedisRateLimiterService} bounds it per project); the
 * dashboard's own calls were.
 *
 * <p><b>Off by default, on purpose.</b> On a self-hosted installation every tenant is the
 * operator's own, so there is no neighbour to be noisy — and the check costs a Redis round trip
 * on every request, which is a real price to pay for a problem you do not have. A shared
 * installation is where it earns that, so it is one setting away rather than absent.
 *
 * <p>An interceptor rather than a filter because this needs to know who is asking, and the
 * filters run at {@code @Order(1)} and {@code (2)} — before authentication has happened at all.
 * By the time a handler is chosen, {@code TenantContext} holds the caller's organization.
 *
 * <p>Callers with no organization pass through untouched: an unauthenticated request has not
 * reached a handler that needs one, and the platform admin runs under the system tenant, which
 * is not a tenant whose share this is.
 */
@Component
@Slf4j
public class OrganizationRateLimitInterceptor implements HandlerInterceptor {

    private final RedisRateLimiterService rateLimiterService;
    private final boolean enabled;
    private final int requestsPerSecond;

    public OrganizationRateLimitInterceptor(
            RedisRateLimiterService rateLimiterService,
            @Value("${rate-limit.per-organization.enabled:false}") boolean enabled,
            @Value("${rate-limit.per-organization.requests-per-second:200}") int requestsPerSecond) {
        this.rateLimiterService = rateLimiterService;
        this.enabled = enabled;
        this.requestsPerSecond = requestsPerSecond;
        if (enabled) {
            log.info("Per-organization API rate limit enabled: {}/sec", requestsPerSecond);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!enabled) {
            return true;
        }

        UUID organizationId = TenantContext.current();
        if (organizationId == null || TenantContext.SYSTEM.equals(organizationId)) {
            return true;
        }

        if (rateLimiterService.tryAcquireForOrganization(organizationId, requestsPerSecond)) {
            return true;
        }

        log.warn("Organization {} exceeded its API rate limit ({}/sec) on {} {}",
                organizationId, requestsPerSecond, request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        // Seconds, because the limiter refills once a second — telling a client to come back
        // later without saying when is how you get an immediate retry loop.
        response.setHeader("Retry-After", "1");
        response.getWriter().write("{\"error\":\"organization_rate_limit\","
                + "\"message\":\"Your organization has exceeded its API rate limit. Please retry shortly.\","
                + "\"status\":429}");
        return false;
    }
}
