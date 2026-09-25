package com.webhook.platform.api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Never put this on sign-in: an account that already carries a typo must still reach the
 * dashboard to fix it. Null passes.
 */
@Documented
@Constraint(validatedBy = DeliverableEmailValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface DeliverableEmail {

    String message() default "This address cannot receive mail";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
