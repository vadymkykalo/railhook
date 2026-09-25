package com.webhook.platform.api.security;

import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.LogSanitizer;
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
 * Keeps one tenant from spending the platform-wide bucket in GlobalRateLimitFilter. Off by
 * default: on a self-hosted install every tenant is the operator's own, and the check costs a
 * Redis round trip per request.
 *
 * <p>An interceptor rather than a filter because the filters run before authentication, so the
 * organization is not known yet.
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
                organizationId,
                requestsPerSecond,
                LogSanitizer.forLog(request.getMethod()),
                LogSanitizer.forLog(request.getRequestURI()));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.setHeader("Retry-After", "1");
        response.getWriter().write("{\"error\":\"organization_rate_limit\","
                + "\"message\":\"Your organization has exceeded its API rate limit. Please retry shortly.\","
                + "\"status\":429}");
        return false;
    }
}
