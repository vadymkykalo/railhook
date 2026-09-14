package com.webhook.platform.api.dto.validation;

import com.webhook.platform.api.domain.EmailAddresses;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

public class EmailRecipientListValidator implements ConstraintValidator<EmailRecipientList, String> {

    private int max;

    @Override
    public void initialize(EmailRecipientList constraint) {
        this.max = constraint.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        List<String> addresses = EmailAddresses.splitList(value);
        return addresses.size() <= max && addresses.stream().allMatch(EmailAddresses::isPlausible);
    }
}
