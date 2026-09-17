package com.webhook.platform.api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A per-second rate limit an organization sets on something it owns, refused above its plan's
 * own {@code rate_limit_per_second}. Without it, the plan's limit was a number on the billing
 * page that any organization could raise for itself by typing a bigger one here.
 *
 * <p>Only checked when billing is enabled: a self-hosted install has no plan to be held to.
 * Null passes.
 */
@Documented
@Constraint(validatedBy = WithinPlanRateLimitValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface WithinPlanRateLimit {

    String message() default "Rate limit exceeds your plan's limit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
