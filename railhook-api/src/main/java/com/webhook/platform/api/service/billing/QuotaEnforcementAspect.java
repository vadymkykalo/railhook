package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.JwtAuthenticationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * AOP aspect that enforces plan quotas ({@link RequireQuota}) and feature flags
 * ({@link RequireFeature}) declaratively.
 *
 * <p>Resolution strategy for identifiers:</p>
 * <ul>
 *   <li>{@code organizationId} — extracted from {@link AuthContext} in method args</li>
 *   <li>{@code projectId} — extracted from {@code @PathVariable("projectId")} or param named "projectId"</li>
 * </ul>
 *
 * <p>When {@code billing.enabled=false} (self-hosted), all checks are no-ops.</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class QuotaEnforcementAspect {

    private final EntitlementService entitlementService;
    private final ProjectRepository projectRepository;

    // ── @RequireQuota ─────────────────────────────────────────────

    @Before("@annotation(requireQuota)")
    public void enforceQuota(JoinPoint joinPoint, RequireQuota requireQuota) {
        if (!entitlementService.isBillingEnabled()) return;

        UUID orgId = resolveOrganizationId(joinPoint);
        if (orgId == null) {
            log.warn("@RequireQuota on {} — cannot resolve organizationId, skipping",
                    joinPoint.getSignature().toShortString());
            return;
        }

        QuotaType quota = requireQuota.value();

        switch (quota) {
            case EVENTS_PER_MONTH -> entitlementService.checkEventQuota();
            case ENDPOINTS_PER_PROJECT -> {
                UUID projectId = extractProjectId(joinPoint);
                if (projectId == null) {
                    log.warn("@RequireQuota(ENDPOINTS_PER_PROJECT) on {} but no projectId found",
                            joinPoint.getSignature().toShortString());
                    return;
                }
                entitlementService.checkEndpointLimit(projectId);
            }
            case PROJECTS -> entitlementService.checkProjectLimit();
            case MEMBERS -> entitlementService.checkMemberLimit();
            case TUNNELS -> entitlementService.checkTunnelLimit();
        }
    }

    // ── @RequireFeature ───────────────────────────────────────────

    @Before("@annotation(requireFeature)")
    public void enforceFeature(JoinPoint joinPoint, RequireFeature requireFeature) {
        if (!entitlementService.isBillingEnabled()) return;

        UUID orgId = resolveOrganizationId(joinPoint);
        if (orgId == null) {
            log.warn("@RequireFeature on {} — cannot resolve organizationId, skipping",
                    joinPoint.getSignature().toShortString());
            return;
        }

        String feature = requireFeature.value();
        if (!entitlementService.hasFeature(feature)) {
            throw new ForbiddenException(
                    "Feature '" + feature + "' is not available on your current plan. Please upgrade.");
        }
    }

    // ── Resolution helpers ────────────────────────────────────────

    /**
     * Resolves organizationId from (in priority order):
     * 1. AuthContext parameter (controllers with AuthContext arg)
     * 2. ApiKeyAuthenticationToken (EventController — API key auth)
     * 3. JwtAuthenticationToken (fallback via SecurityContext)
     */
    private UUID resolveOrganizationId(JoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof AuthContext auth) {
                return auth.organizationId();
            }
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getOrganizationId();
        }
        if (authentication instanceof ApiKeyAuthenticationToken apiKey && apiKey.getProjectId() != null) {
            return projectRepository.findById(apiKey.getProjectId())
                    .map(Project::getOrganizationId)
                    .orElse(null);
        }

        return null;
    }

    private UUID extractProjectId(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Object[] args = joinPoint.getArgs();

        Method method;
        try {
            method = joinPoint.getTarget().getClass().getMethod(
                    signature.getName(), signature.getParameterTypes());
        } catch (NoSuchMethodException e) {
            method = signature.getMethod();
        }

        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        String[] paramNames = signature.getParameterNames();

        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof UUID)) continue;
            for (Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof PathVariable pv) {
                    String pvName = !pv.value().isEmpty() ? pv.value() : pv.name();
                    if ("projectId".equals(pvName)) {
                        return (UUID) args[i];
                    }
                }
            }
        }

        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if ("projectId".equals(paramNames[i]) && args[i] instanceof UUID) {
                    return (UUID) args[i];
                }
            }
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            return apiKey.getProjectId();
        }

        return null;
    }
}
