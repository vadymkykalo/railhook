package com.webhook.platform.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires the {@code orgId} path variable to match the caller's organization claim.
 * {@link OrgAccessAspect} throws
 * {@link com.webhook.platform.api.exception.ForbiddenException} on a mismatch.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireOrgAccess {

    String orgIdParam() default "orgId";
}
