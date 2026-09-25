package com.webhook.platform.api.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a method across every organization. Work without a request (schedulers, consumers,
 * startup) must declare its scope, or its first query throws.
 *
 * <p>Root scope removes the read filter only. On insert under root, Hibernate keeps the
 * {@code @TenantId} value as given, so a tenant-scoped row must have {@code organizationId} set
 * explicitly, or be written inside {@link TenantContext#runAs}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemTenant {

    /** Why this method has no tenant. */
    String value() default "";
}
