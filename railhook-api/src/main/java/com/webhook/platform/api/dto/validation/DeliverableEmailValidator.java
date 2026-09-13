package com.webhook.platform.api.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Optional;

public class DeliverableEmailValidator implements ConstraintValidator<DeliverableEmail, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        Optional<String> meant = EmailTypoPolicy.impossibleTldCorrection(value);
        if (meant.isEmpty()) {
            return true;
        }
        // The corrected address is what makes the refusal actionable. Escaped because the message
        // is an EL template, and an address is user input.
        String escaped = meant.get().replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
                .replace("$", "\\$");
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        "This address cannot receive mail. Did you mean " + escaped + "?")
                .addConstraintViolation();
        return false;
    }
}
