package com.webhook.platform.api.security;

import com.webhook.platform.api.exception.ForbiddenException;
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
 * AOP aspect that enforces tenant isolation for methods annotated with
 * {@link RequireOrgAccess}.
 *
 * <p>
 * The aspect runs <strong>before</strong> the target method and:
 * </p>
 * <ol>
 * <li>Extracts the {@code orgId} path-variable value from the method
 * arguments.</li>
 * <li>Extracts the authenticated user's {@code organizationId} from the
 * JWT.</li>
 * <li>If they do not match, throws {@link ForbiddenException} (HTTP 403).</li>
 * </ol>
 */
@Slf4j
@Aspect
@Component
public class OrgAccessAspect {

    @Before("@annotation(requireOrgAccess)")
    public void checkOrgAccess(JoinPoint joinPoint, RequireOrgAccess requireOrgAccess) {
        UUID pathOrgId = extractOrgId(joinPoint, requireOrgAccess.orgIdParam());
        if (pathOrgId == null) {
            log.warn("@RequireOrgAccess: could not resolve parameter '{}' on method {}",
                    requireOrgAccess.orgIdParam(),
                    joinPoint.getSignature().toShortString());
            throw new ForbiddenException("Access denied: organization ID not found in request");
        }

        AuthContext authContext = extractAuthContext(joinPoint);
        if (authContext != null) {
            UUID tokenOrgId = authContext.organizationId();
            if (!pathOrgId.equals(tokenOrgId)) {
                log.warn("Tenant isolation violation: org {} attempted to access org {}",
                        tokenOrgId, pathOrgId);
                throw new ForbiddenException("Access denied to organization");
            }
            return;
        }

        // Fallback for methods without AuthContext parameter
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            if (!pathOrgId.equals(jwtAuth.getOrganizationId())) {
                log.warn("Tenant isolation violation: user {} (org {}) attempted to access org {}",
                        jwtAuth.getUserId(), jwtAuth.getOrganizationId(), pathOrgId);
                throw new ForbiddenException("Access denied to organization");
            }
        } else if (authentication instanceof ApiKeyAuthenticationToken) {
            // API key org is resolved via AuthContext; if no AuthContext param, deny
            throw new ForbiddenException("Access denied: authentication required");
        } else {
            throw new ForbiddenException("Access denied: authentication required");
        }
    }

    private AuthContext extractAuthContext(JoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof AuthContext) {
                return (AuthContext) arg;
            }
        }
        return null;
    }

    /**
     * Resolves the {@code orgId} value from the method's arguments.
     * Uses the target class method (not the proxy) to read annotations correctly.
     */
    private UUID extractOrgId(JoinPoint joinPoint, String orgIdParamName) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Object[] args = joinPoint.getArgs();

        // Get method from the TARGET class, not from proxy — annotations are on the
        // real class
        Method method;
        try {
            method = joinPoint.getTarget().getClass().getMethod(
                    signature.getName(), signature.getParameterTypes());
        } catch (NoSuchMethodException e) {
            method = signature.getMethod();
        }

        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        String[] paramNames = signature.getParameterNames();

        // 1. Match by @PathVariable annotation value/name
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof UUID)) {
                continue;
            }
            for (Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof PathVariable) {
                    PathVariable pv = (PathVariable) annotation;
                    String pvName = !pv.value().isEmpty() ? pv.value() : pv.name();
                    if (orgIdParamName.equals(pvName)) {
                        return (UUID) args[i];
                    }
                }
            }
        }

        // 2. Fallback: match by parameter name
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (orgIdParamName.equals(paramNames[i]) && args[i] instanceof UUID) {
                    return (UUID) args[i];
                }
            }
        }

        return null;
    }
}
