package com.webhook.platform.api.tenancy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Ordered ahead of {@code @Transactional}: Hibernate reads the tenant when it opens the session,
 * so a scope entered inside the transaction would be too late.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SystemTenantAspect {

    @Around("@annotation(com.webhook.platform.api.tenancy.SystemTenant)")
    public Object runAsSystem(ProceedingJoinPoint joinPoint) throws Throwable {
        UUID previous = TenantContext.set(TenantContext.SYSTEM);
        try {
            return joinPoint.proceed();
        } finally {
            TenantContext.restore(previous);
        }
    }
}
