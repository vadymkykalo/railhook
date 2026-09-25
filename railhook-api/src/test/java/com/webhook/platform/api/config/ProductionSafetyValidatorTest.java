package com.webhook.platform.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSafetyValidatorTest {

    private static final String STRONG_SECRET_1 = "kQ2v9ZpL7xR4mN8sT1wY6bC3dF0gH5jK9nM2pQ7rS4t";
    private static final String STRONG_SECRET_2 = "hB6yN3wQ8vX1rT5mK9pL2sD7fG4jC0zA6eR3uY8iO1w";
    private static final String STRONG_SECRET_3 = "wF4tR9nB2kL7xQ5mP8sV1yC6dH3jG0zA9eU4iO7rT2w";
    private static final String STRONG_SECRET_4 = "pL8xC3vN6bM1kQ9wR4tY7sD2fG5jH0zA3eU8iO1rW6t";
    private static final String STRONG_SECRET_5 = "zA5eU2iO9rW6tF3nB8kL1xQ4mP7sV0yC6dH9jG2zA5e";

    private ProductionSafetyValidator valid() {
        ProductionSafetyValidator v = new ProductionSafetyValidator();
        ReflectionTestUtils.setField(v, "appEnv", "production");
        ReflectionTestUtils.setField(v, "encryptionKey", STRONG_SECRET_1);
        ReflectionTestUtils.setField(v, "encryptionSalt", STRONG_SECRET_2);
        ReflectionTestUtils.setField(v, "jwtSecret", STRONG_SECRET_3);
        ReflectionTestUtils.setField(v, "dbPassword", STRONG_SECRET_4);
        ReflectionTestUtils.setField(v, "redisPassword", STRONG_SECRET_5);
        ReflectionTestUtils.setField(v, "corsAllowedOrigins", "https://app.example.com");
        ReflectionTestUtils.setField(v, "allowPrivateIps", false);
        ReflectionTestUtils.setField(v, "swaggerEnabled", false);
        ReflectionTestUtils.setField(v, "billingEnabled", false);
        ReflectionTestUtils.setField(v, "emailEnabled", false);
        ReflectionTestUtils.setField(v, "captchaSecretKey", "");
        return v;
    }

    private ProductionSafetyValidator hosted(boolean emailEnabled, String captchaSecretKey) {
        ProductionSafetyValidator v = valid();
        ReflectionTestUtils.setField(v, "billingEnabled", true);
        ReflectionTestUtils.setField(v, "emailEnabled", emailEnabled);
        ReflectionTestUtils.setField(v, "captchaSecretKey", captchaSecretKey);
        return v;
    }

    @Test
    void developmentNeverRunsTheChecks() {
        ProductionSafetyValidator v = new ProductionSafetyValidator();
        ReflectionTestUtils.setField(v, "appEnv", "development");
        ReflectionTestUtils.setField(v, "encryptionKey", "dev_encryption_key_32_chars_min!");
        ReflectionTestUtils.setField(v, "encryptionSalt", "dev_encryption_salt_16_chars");
        ReflectionTestUtils.setField(v, "jwtSecret", "dev_jwt_secret_key_32_chars_minimum");
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);
        ReflectionTestUtils.setField(v, "swaggerEnabled", true);

        assertDoesNotThrow(v::validateProductionConfig);
    }

    @Test
    void aValidProductionConfigPasses() {
        assertDoesNotThrow(valid()::validateProductionConfig);
    }

    // A missing required variable is already refused by docker-compose's ${VAR:?} guard.
    @Test
    void aBlankSecretIsNotFlaggedTwice() {
        ProductionSafetyValidator v = valid();
        ReflectionTestUtils.setField(v, "redisPassword", "");

        assertDoesNotThrow(v::validateProductionConfig);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "encryptionKey      | dev_encryption_key_32_chars_min!         | WEBHOOK_ENCRYPTION_KEY",
            "jwtSecret          | please_changeme_before_deploying_to_prod | JWT_SECRET",
            "jwtSecret          | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa         | JWT_SECRET",
            "encryptionSalt     | abcabcabcabc                             | WEBHOOK_ENCRYPTION_SALT",
            "redisPassword      | webhook_redis_pass                       | REDIS_PASSWORD",
            "dbPassword         | webhook_dev_pass_12345                   | POSTGRES_PASSWORD",
            "corsAllowedOrigins | http://localhost:5173,http://localhost:3000 | CORS_ALLOWED_ORIGINS",
    })
    void aPlaceholderLowEntropyOrShippedDefaultValueIsRejected(String field, String value, String variable) {
        ProductionSafetyValidator v = valid();
        ReflectionTestUtils.setField(v, field, value);

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains(variable));
    }

    @Test
    void everyViolationIsReportedTogetherWhateverTheCaseOfTheEnvironment() {
        ProductionSafetyValidator v = valid();
        ReflectionTestUtils.setField(v, "appEnv", "PRODUCTION");
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);
        ReflectionTestUtils.setField(v, "swaggerEnabled", true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("WEBHOOK_ALLOW_PRIVATE_IPS"));
        assertTrue(ex.getMessage().contains("SWAGGER_ENABLED"));
    }

    @Test
    void hostedModeWithMailAndACaptchaIsAccepted() {
        assertDoesNotThrow(hosted(true, "0x4AAAAAAA-turnstile-secret")::validateProductionConfig);
    }

    // Without mail, registration marks an unproven address verified.
    @Test
    void hostedModeWithoutMailIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                hosted(false, "0x4AAAAAAA-turnstile-secret")::validateProductionConfig);
        assertTrue(e.getMessage().contains("EMAIL_ENABLED=false"));
    }

    // The registration rate limit is per address, and a signup farm has plenty of those.
    @Test
    void hostedModeWithoutACaptchaIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                hosted(true, "")::validateProductionConfig);
        assertTrue(e.getMessage().contains("CAPTCHA_SECRET_KEY"));
    }
}
