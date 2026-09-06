package com.webhook.platform.api.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a method runs across every organization rather than inside one.
 *
 * <p>The counterpart to the request path: {@code TenantContextFilter} scopes a request to its
 * caller's organization, and everything that is not a request has to say what it is instead.
 * Schedulers, Kafka consumers and startup work have no caller, so without this they would hit
 * {@link TenantNotResolvedException} on their first query — deliberately, because the two silent
 * alternatives are both wrong (see {@link OrganizationTenantResolver}).
 *
 * <p>Declared at the seam, so a reviewer sees the answer in the signature and
 * {@code BackgroundWorkTenantDeclarationTest} can fail the build on a new {@code @Scheduled}
 * method that declares nothing.
 *
 * <p>Root scope removes the read filter but not the care needed to write. On insert Hibernate
 * takes the {@code @TenantId} property's value only under root, and otherwise stamps the current
 * tenant over it. So a system-scoped method inserting a tenant-scoped row must set
 * {@code organizationId} itself, or the row belongs to nobody; where that is awkward, enter
 * {@link TenantContext#runAs} per subject before writing.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemTenant {

    /** Why this method has no tenant. */
    String value() default "";
}
