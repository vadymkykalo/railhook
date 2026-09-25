package com.webhook.platform.api.service.billing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Needs an {@code AuthContext} argument (and {@code projectId} for project quotas), or it does nothing. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireQuota {
    QuotaType value();
}
