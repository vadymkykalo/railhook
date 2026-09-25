package com.webhook.platform.api.dto;

import com.webhook.platform.api.dto.validation.WithinPlanRateLimitValidator;
import com.webhook.platform.api.service.billing.EntitlementService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Unbounded, a Source's limit let any organization step past its plan's rate_limit_per_second.
class IncomingSourceRequestValidationTest {

    private final EntitlementService entitlementService = mock(EntitlementService.class);
    private Validator validator;

    @BeforeEach
    void setUp() {
        var configuration = Validation.byDefaultProvider().configure();
        ConstraintValidatorFactory defaults = configuration.getDefaultConstraintValidatorFactory();
        validator = configuration
                .constraintValidatorFactory(new ConstraintValidatorFactory() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                        return key == WithinPlanRateLimitValidator.class
                                ? (T) new WithinPlanRateLimitValidator(entitlementService)
                                : defaults.getInstance(key);
                    }

                    @Override
                    public void releaseInstance(ConstraintValidator<?, ?> instance) {
                    }
                })
                .buildValidatorFactory()
                .getValidator();
        when(entitlementService.isBillingEnabled()).thenReturn(true);
        when(entitlementService.getRateLimit()).thenReturn(10);
    }

    @Test
    void aRateLimitBelowOneIsRefused() {
        assertThat(violationsFor(0)).isNotEmpty();
        assertThat(violationsFor(-5)).isNotEmpty();
    }

    @Test
    void aRateLimitAboveThePlansIsRefused() {
        Set<ConstraintViolation<IncomingSourceRequest>> violations = violationsFor(1_000_000);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("10"));
    }

    @Test
    void aRateLimitWithinThePlanIsAccepted() {
        assertThat(violationsFor(10)).isEmpty();
        assertThat(violationsFor(null)).isEmpty();
    }

    @Test
    void withoutBillingOnlyTheSanityBoundApplies() {
        when(entitlementService.isBillingEnabled()).thenReturn(false);

        assertThat(violationsFor(5_000)).isEmpty();
        assertThat(violationsFor(1_000_000)).isNotEmpty();
    }

    private Set<ConstraintViolation<IncomingSourceRequest>> violationsFor(Integer rateLimit) {
        return validator.validateProperty(
                IncomingSourceRequest.builder().name("stripe").rateLimitPerSecond(rateLimit).build(),
                "rateLimitPerSecond");
    }
}
