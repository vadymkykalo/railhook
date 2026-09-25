package com.webhook.platform.api.dto.validation;

import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.dto.ChangeEmailRequest;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.dto.UpdateBillingRequest;
import com.webhook.platform.api.domain.enums.MembershipRole;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Refuses only endings no registry delegates; a popular-domain typo stays a dashboard suggestion.
class DeliverableEmailTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @CsvSource({
            "wheelet1228@gmail.con, wheelet1228@gmail.com",
            "a@gmail.cmo, a@gmail.com",
            "a@acme.comm, a@acme.com",
            "a@ukr.nt, a@ukr.net",
            "a@example.ogr, a@example.org",
    })
    void impossibleEndingIsRefusedWithTheLikelyAddress(String typed, String meant) {
        assertThat(EmailTypoPolicy.impossibleTldCorrection(typed)).contains(meant);

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(register(typed));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("email");
            assertThat(v.getMessage()).contains(meant);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@gmail.com", "a@gmial.com", "a@icloud.co", "a@x.om", "a@x.cm", "a@i.ua", "a@example.co.uk"})
    void anythingThatCanReceiveMailPasses(String email) {
        assertThat(EmailTypoPolicy.impossibleTldCorrection(email)).isEmpty();
        assertThat(validator.validate(register(email))).isEmpty();
    }

    @Test
    void everyPlaceAnAddressIsEnteredChecksIt() {
        assertThat(validator.validate(AddMemberRequest.builder()
                .email("teammate@gmail.con").role(MembershipRole.DEVELOPER).build())).hasSize(1);
        UpdateBillingRequest billing = new UpdateBillingRequest();
        billing.setBillingEmail("billing@acme.con");
        assertThat(validator.validate(billing)).hasSize(1);
        assertThat(validator.validate(ChangeEmailRequest.builder().newEmail("me@gmail.con").build())).hasSize(1);
    }

    // Whoever registered with the typo must be able to sign in to correct it.
    @Test
    void signInDoesNotCheckIt() {
        assertThat(validator.validate(LoginRequest.builder()
                .email("wheelet1228@gmail.con").password("whatever").build())).isEmpty();
    }

    private static RegisterRequest register(String email) {
        return RegisterRequest.builder()
                .email(email)
                .password("A-str0ng-passphrase!")
                .organizationName("Acme")
                .build();
    }
}
