package com.webhook.platform.api.service.billing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Refuses the call unless the organization's plan has the feature. Every feature is on when billing is disabled. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireFeature {
    /** A key of plans.features, e.g. "workflows". */
    String value();
}
