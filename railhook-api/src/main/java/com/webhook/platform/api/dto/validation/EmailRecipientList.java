package com.webhook.platform.api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Null and blank are valid: on an update they mean "leave as is" and "clear". */
@Documented
@Constraint(validatedBy = EmailRecipientListValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EmailRecipientList {

    String message() default "must be a comma-separated list of at most {max} email addresses";

    int max() default 10;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
