package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.JwtAuthenticationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
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
    private final OrganizationRepository organizationRepository;
    private final PlatformTransactionManager transactionManager;

    // ── @RequireQuota ─────────────────────────────────────────────

    @Around("@annotation(requireQuota)")
    public Object enforceQuota(ProceedingJoinPoint joinPoint, RequireQuota requireQuota) throws Throwable {
        if (!entitlementService.isBillingEnabled()) return joinPoint.proceed();

        UUID orgId = resolveOrganizationId(joinPoint);
        if (orgId == null) {
            log.warn("@RequireQuota on {} — cannot resolve organizationId, skipping",
                    joinPoint.getSignature().toShortString());
            return joinPoint.proceed();
        }

        QuotaType quota = requireQuota.value();

        switch (quota) {
            case ENDPOINTS_PER_PROJECT -> {
                UUID projectId = extractProjectId(joinPoint);
                if (projectId == null) {
                    log.warn("@RequireQuota(ENDPOINTS_PER_PROJECT) on {} but no projectId found",
                            joinPoint.getSignature().toShortString());
                    return joinPoint.proceed();
                }
                return underOrganizationLock(orgId, () -> entitlementService.checkEndpointLimit(projectId), joinPoint);
            }
            case PROJECTS -> {
                return underOrganizationLock(orgId, entitlementService::checkProjectLimit, joinPoint);
            }
            case MEMBERS -> {
                return underOrganizationLock(orgId, entitlementService::checkMemberLimit, joinPoint);
            }
            case TUNNELS -> entitlementService.checkTunnelLimit();
        }
        return joinPoint.proceed();
    }

    /**
     * Runs the count and the create it guards in one transaction holding the Organization's row
     * lock, which the create's own transaction joins.
     *
     * <p>A count taken before the create's transaction, with nothing held between it and the
     * insert, let requests released together at one below the limit all count below it and all
     * get through. Holding the lock, the second create waits for the first to commit and then
     * counts it — the same lock the active-tunnel limit takes.
     */
    private Object underOrganizationLock(UUID orgId, Runnable check, ProceedingJoinPoint joinPoint) throws Throwable {
        TransactionStatus transaction = transactionManager.getTransaction(TransactionDefinition.withDefaults());
        Object result;
        try {
            organizationRepository.lockById(orgId);
            check.run();
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            transactionManager.rollback(transaction);
            throw failure;
        }
        transactionManager.commit(transaction);
        return result;
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
