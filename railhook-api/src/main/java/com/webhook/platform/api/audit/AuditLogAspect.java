package com.webhook.platform.api.audit;

import com.webhook.platform.api.domain.entity.AuditLog;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import com.webhook.platform.api.security.JwtAuthenticationToken;
import com.webhook.platform.api.security.PlatformAdminUserAuthenticationToken;
import com.webhook.platform.api.security.PortalSessionAuthenticationToken;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.api.tenancy.TenantPropagatingTaskDecorator;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    private final AuditLogRepository auditLogRepository;
    private final TrustedProxyResolver trustedProxyResolver;
    private final ObjectMapper objectMapper;
    /**
     * Single-threaded so audit writes stay ordered, daemon so they never hold up shutdown. Wrapped
     * because a hand-built pool gets no tenant-propagating decorator, and AuditLog is tenant-scoped.
     */
    private final ExecutorService executor = TenantPropagatingTaskDecorator.wrap(
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "audit-log-writer");
                t.setDaemon(true);
                return t;
            }));

    public AuditLogAspect(AuditLogRepository auditLogRepository, TrustedProxyResolver trustedProxyResolver) {
        this.auditLogRepository = auditLogRepository;
        this.trustedProxyResolver = trustedProxyResolver;
        this.objectMapper = new ObjectMapper()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        long start = System.currentTimeMillis();
        String action = auditable.action().name();
        String resourceType = resolveResourceType(auditable, joinPoint);
        UUID resourceId = extractResourceId(joinPoint);
        UUID userId = null;
        UUID orgId = null;
        String clientIp = resolveClientIp();
        String details = extractDetails(joinPoint);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            userId = jwtAuth.getUserId();
            // A platform admin's token names their own organization, not the one they act on.
            orgId = auth instanceof PlatformAdminUserAuthenticationToken ? null : jwtAuth.getOrganizationId();
        } else if (auth instanceof ApiKeyAuthenticationToken apiKeyAuth) {
            orgId = apiKeyAuth.getOrganizationId();
        } else if (auth instanceof PortalSessionAuthenticationToken portalAuth) {
            orgId = portalAuth.getOrganizationId();
        }

        // Only for acceptInvite: it is @SystemTenant and the invite's organization is not the
        // caller's, so it arrives as a parameter. Elsewhere that parameter is forbidden.
        if (orgId == null) {
            orgId = extractOrganizationId(joinPoint);
        }

        final UUID uid = userId;
        final UUID oid = orgId;
        final String ip = clientIp;

        try {
            Object result = joinPoint.proceed();
            int durationMs = (int) (System.currentTimeMillis() - start);

            executor.execute(() -> saveAuditLog(action, resourceType, resourceId, uid, oid, "SUCCESS", null, durationMs, ip, details));
            return result;
        } catch (Throwable ex) {
            int durationMs = (int) (System.currentTimeMillis() - start);
            String errorMsg = ex.getMessage() != null ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 500)) : null;

            executor.execute(() -> saveAuditLog(action, resourceType, resourceId, uid, oid, "FAILURE", errorMsg, durationMs, ip, details));
            throw ex;
        }
    }

    public void recordAsync(String action, String resourceType, UUID resourceId,
                            UUID userId, UUID orgId, String status, String errorMessage,
                            int durationMs, String clientIp, String details) {
        executor.execute(() -> saveAuditLog(action, resourceType, resourceId, userId, orgId, status, errorMessage,
                durationMs, clientIp, details));
    }

    /** Written on the writer thread, so the entry survives a rollback of the request. */
    public void record(AuditAction action, String resourceType, UUID userId, UUID organizationId,
                       String status, String errorMessage, String details) {
        String ip = resolveClientIp();
        executor.execute(() -> saveAuditLog(action.name(), resourceType, null, userId, organizationId,
                status, errorMessage, 0, ip, details));
    }

    public void saveAuditLog(String action, String resourceType, UUID resourceId,
                              UUID userId, UUID orgId, String status, String errorMessage,
                              int durationMs, String clientIp, String details) {
        // The row's organization is not always the ambient one, so state it. Unauthenticated
        // actions have none and go under SYSTEM, which no tenant-scoped reader matches.
        UUID rowTenant = orgId != null ? orgId : TenantContext.SYSTEM;
        try {
            TenantContext.runAs(rowTenant, () -> persist(action, resourceType, resourceId, userId, rowTenant,
                    status, errorMessage, durationMs, clientIp, details));
        } catch (Exception e) {
            log.warn("Failed to save audit log: action={}, organizationId={}", action, orgId, e);
        }
    }

    private void persist(String action, String resourceType, UUID resourceId,
                         UUID userId, UUID orgId, String status, String errorMessage,
                         int durationMs, String clientIp, String details) {
        auditLogRepository.save(AuditLog.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .userId(userId)
                .organizationId(orgId)
                .status(status)
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .clientIp(clientIp)
                .details(details)
                .build());
    }

    private String resolveResourceType(Auditable auditable, ProceedingJoinPoint joinPoint) {
        if (!auditable.resourceType().isEmpty()) {
            return auditable.resourceType();
        }
        String className = joinPoint.getTarget().getClass().getSimpleName();
        return className.replace("Service", "");
    }

    private UUID extractResourceId(ProceedingJoinPoint joinPoint) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = joinPoint.getArgs();

        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (args[i] instanceof UUID && isResourceIdParam(names[i])) {
                    return (UUID) args[i];
                }
            }
        }

        for (Object arg : args) {
            if (arg instanceof UUID) {
                return (UUID) arg;
            }
        }
        return null;
    }

    private UUID extractOrganizationId(ProceedingJoinPoint joinPoint) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = joinPoint.getArgs();

        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (args[i] instanceof UUID && "organizationId".equals(names[i])) {
                    return (UUID) args[i];
                }
            }
        }
        return null;
    }

    private boolean isResourceIdParam(String name) {
        return name.equals("id") || name.endsWith("Id") || name.endsWith("ID");
    }

    private String extractDetails(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            String[] names = sig.getParameterNames();
            Object[] args = joinPoint.getArgs();
            if (names == null) return null;

            Map<String, Object> details = new LinkedHashMap<>();
            for (int i = 0; i < names.length; i++) {
                if (args[i] == null) continue;
                if (args[i] instanceof UUID) continue;
                if (args[i] instanceof String || args[i] instanceof Number || args[i] instanceof Boolean) continue;
                if (args[i] instanceof Enum) continue;
                String className = args[i].getClass().getSimpleName();
                if (className.endsWith("Request") || className.endsWith("Role")) {
                    details.put(names[i], args[i]);
                }
            }
            if (details.isEmpty()) return null;
            String json = objectMapper.writeValueAsString(details);
            return json.length() > 2000 ? json.substring(0, 2000) : json;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            return trustedProxyResolver.resolve(request);
        } catch (Exception e) {
            return null;
        }
    }
}
