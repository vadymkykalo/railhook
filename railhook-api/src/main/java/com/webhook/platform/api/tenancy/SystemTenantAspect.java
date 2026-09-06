package com.webhook.platform.api.tenancy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs a {@link SystemTenant} method inside {@link TenantContext#SYSTEM} and restores the previous
 * scope afterwards.
 *
 * <p>Ordered ahead of everything else, {@code @Transactional} included. The tenant is read when
 * Hibernate opens a session, so a scope entered <em>inside</em> a transaction would be too late
 * for the session that transaction already opened.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SystemTenantAspect {

    @Around("@annotation(com.webhook.platform.api.tenancy.SystemTenant)")
    public Object runAsSystem(ProceedingJoinPoint joinPoint) throws Throwable {
        java.util.UUID previous = TenantContext.set(TenantContext.SYSTEM);
        try {
            return joinPoint.proceed();
        } finally {
            TenantContext.restore(previous);
        }
    }
}
