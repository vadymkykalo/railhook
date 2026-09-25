package com.webhook.platform.api.dto.validation;

import com.webhook.platform.api.service.billing.EntitlementService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Reads the caller's plan, so it only works once the request is scoped to an organization. */
public class WithinPlanRateLimitValidator implements ConstraintValidator<WithinPlanRateLimit, Integer> {

    private final EntitlementService entitlementService;

    public WithinPlanRateLimitValidator(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        if (value == null || !entitlementService.isBillingEnabled()) {
            return true;
        }
        int planLimit = entitlementService.getRateLimit();
        if (planLimit <= 0 || value <= planLimit) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        "Rate limit must be at most " + planLimit + " per second on your plan")
                .addConstraintViolation();
        return false;
    }
}
