package com.webhook.platform.api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An address somebody is about to send mail to, refused when its ending cannot receive any.
 *
 * <p>For fields where an address is <em>entered</em> — registration, an invite, a billing
 * contact, a new account address. Never on sign-in: an account that already carries a typo has
 * to be able to reach the dashboard to fix it. Null passes; pair with {@code @NotBlank} where the
 * field is required.
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
