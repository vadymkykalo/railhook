package com.webhook.platform.api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Refuses a rate limit above the plan's {@code rate_limit_per_second}. Only checked when billing
 * is enabled; null passes.
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
