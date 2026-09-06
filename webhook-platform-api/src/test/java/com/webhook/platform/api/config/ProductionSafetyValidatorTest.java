package com.webhook.platform.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProductionSafetyValidator previously had no tests at all — ironic for the
 * component that gates unsafe production config. These cover: dev-default secrets
 * rejected, low-entropy secrets rejected, secrets left unchanged from .env.dist
 * rejected, and a genuinely valid production config accepted.
 */
class ProductionSafetyValidatorTest {

    // A realistic, high-entropy secret — the kind CryptoUtils.generateSecureToken(32)
    // or `openssl rand -base64 32` would actually produce.
    private static final String STRONG_SECRET_1 = "kQ2v9ZpL7xR4mN8sT1wY6bC3dF0gH5jK9nM2pQ7rS4t";
    private static final String STRONG_SECRET_2 = "hB6yN3wQ8vX1rT5mK9pL2sD7fG4jC0zA6eR3uY8iO1w";
    private static final String STRONG_SECRET_3 = "wF4tR9nB2kL7xQ5mP8sV1yC6dH3jG0zA9eU4iO7rT2w";
    private static final String STRONG_SECRET_4 = "pL8xC3vN6bM1kQ9wR4tY7sD2fG5jH0zA3eU8iO1rW6t";
    private static final String STRONG_SECRET_5 = "zA5eU2iO9rW6tF3nB8kL1xQ4mP7sV0yC6dH9jG2zA5e";

    private ProductionSafetyValidator newValidator() {
        return new ProductionSafetyValidator();
    }

    private void setValid(ProductionSafetyValidator v) {
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
        ReflectionTestUtils.setField(v, "billingProvider", "noop");
        ReflectionTestUtils.setField(v, "captchaSecretKey", "");
    }

    // -----------------------------------------------------------------
    // Non-production: never runs the checks, regardless of config
    // -----------------------------------------------------------------

    @Test
    void testDevelopmentEnv_neverThrows_evenWithDevSecrets() {
        ProductionSafetyValidator v = newValidator();
        ReflectionTestUtils.setField(v, "appEnv", "development");
        ReflectionTestUtils.setField(v, "encryptionKey", "dev_encryption_key_32_chars_min!");
        ReflectionTestUtils.setField(v, "encryptionSalt", "dev_encryption_salt_16_chars");
        ReflectionTestUtils.setField(v, "jwtSecret", "dev_jwt_secret_key_32_chars_minimum");
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);
        ReflectionTestUtils.setField(v, "swaggerEnabled", true);

        assertDoesNotThrow(v::validateProductionConfig);
    }

    // -----------------------------------------------------------------
    // Valid production config: accepted
    // -----------------------------------------------------------------

    @Test
    void testValidProductionConfig_passes() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);

        assertDoesNotThrow(v::validateProductionConfig);
    }

    // -----------------------------------------------------------------
    // Dev-default / placeholder secrets rejected
    // -----------------------------------------------------------------

    @Test
    void testProduction_devEncryptionKey_rejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "encryptionKey", "dev_encryption_key_32_chars_min!");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("WEBHOOK_ENCRYPTION_KEY"));
    }

    @Test
    void testProduction_jwtSecretContainingChangeme_rejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "jwtSecret", "please_changeme_before_deploying_to_prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("JWT_SECRET"));
    }

    @Test
    void testProduction_blankSecret_isNotFlagged() {
        // A genuinely missing required var is already caught by docker-compose's
        // ${VAR:?must be set} guard before the JVM starts; the validator itself
        // should not fail startup a second time over an empty string.
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "redisPassword", "");

        assertDoesNotThrow(v::validateProductionConfig);
    }

    // -----------------------------------------------------------------
    // Low-entropy secrets rejected — the exact footgun:
    // a fixed substring denylist lets a repeated-character value through.
    // -----------------------------------------------------------------

    @Test
    void testProduction_repeatedCharacterJwtSecret_rejectedByEntropyFloor() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "jwtSecret", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("JWT_SECRET"));
        assertTrue(ex.getMessage().toLowerCase().contains("entropy"));
    }

    @Test
    void testProduction_shortLowEntropyEncryptionSalt_rejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "encryptionSalt", "abcabcabcabc");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("WEBHOOK_ENCRYPTION_SALT"));
    }

    // -----------------------------------------------------------------
    // Values unchanged from the shipped .env.dist defaults rejected, even
    // though they aren't in the fixed placeholder-substring list.
    // -----------------------------------------------------------------

    @Test
    void testProduction_unchangedRedisPasswordFromEnvDist_rejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "redisPassword", "webhook_redis_pass");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("REDIS_PASSWORD"));
        assertTrue(ex.getMessage().contains("shipped default"));
    }

    @Test
    void testProduction_unchangedDbPasswordFromEnvDist_rejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "dbPassword", "webhook_dev_pass_12345");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("POSTGRES_PASSWORD"));
    }

    // -----------------------------------------------------------------
    // Other production-only checks
    // -----------------------------------------------------------------

    @Test
    void testProduction_allowPrivateIpsTrue_rejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("WEBHOOK_ALLOW_PRIVATE_IPS"));
    }

    @Test
    void testProduction_swaggerEnabledTrue_rejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "swaggerEnabled", true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("SWAGGER_ENABLED"));
    }

    @Test
    void testProduction_corsStillLocalhost_rejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "corsAllowedOrigins", "http://localhost:5173,http://localhost:3000");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("CORS_ALLOWED_ORIGINS"));
    }

    @Test
    void testProduction_corsRealDomain_passes() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "corsAllowedOrigins", "https://dashboard.mycompany.com");

        assertDoesNotThrow(v::validateProductionConfig);
    }

    @Test
    void testProductionCaseInsensitive() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "appEnv", "PRODUCTION");
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);

        assertThrows(IllegalStateException.class, v::validateProductionConfig);
    }

    @Test
    void testMultipleViolations_allReportedTogether() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);
        ReflectionTestUtils.setField(v, "swaggerEnabled", true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(ex.getMessage().contains("WEBHOOK_ALLOW_PRIVATE_IPS"));
        assertTrue(ex.getMessage().contains("SWAGGER_ENABLED"));
    }
    // -----------------------------------------------------------------
    // Hosted mode: BILLING_ENABLED is the only thing separating a paid
    // multi-tenant deployment from a self-hosted one, so the settings that
    // have to accompany it are checked rather than assumed.
    // -----------------------------------------------------------------

    @Test
    void selfHostedIsUnaffectedByTheHostedModeChecks() {
        // Billing off is the shipped default and the whole of what makes self-hosting
        // ungated; nothing about mail or a payment provider is required there.
        ProductionSafetyValidator v = newValidator();
        setValid(v);

        assertDoesNotThrow(v::validateProductionConfig);
    }

    @Test
    void billingWithoutMailIsRejected() {
        // Registration marks an account verified when nothing can be sent to it. Open
        // registration plus that is a paid tier behind an unproven address.
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "billingEnabled", true);
        ReflectionTestUtils.setField(v, "billingProvider", "stripe");
        ReflectionTestUtils.setField(v, "captchaSecretKey", "0x4AAAAAAA-turnstile-secret");
        ReflectionTestUtils.setField(v, "emailEnabled", false);

        IllegalStateException e = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(e.getMessage().contains("EMAIL_ENABLED=false"));
    }

    @Test
    void billingWithTheNoOpProviderIsRejected() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "billingEnabled", true);
        ReflectionTestUtils.setField(v, "emailEnabled", true);
        ReflectionTestUtils.setField(v, "captchaSecretKey", "0x4AAAAAAA-turnstile-secret");
        ReflectionTestUtils.setField(v, "billingProvider", "noop");

        IllegalStateException e = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(e.getMessage().contains("BILLING_DEFAULT_PROVIDER"));
    }

    @Test
    void aCoherentHostedConfigurationIsAccepted() {
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "billingEnabled", true);
        ReflectionTestUtils.setField(v, "emailEnabled", true);
        ReflectionTestUtils.setField(v, "billingProvider", "stripe");
        ReflectionTestUtils.setField(v, "captchaSecretKey", "0x4AAAAAAA-turnstile-secret");

        assertDoesNotThrow(v::validateProductionConfig);
    }
    @Test
    void billingWithoutACaptchaIsRejected() {
        // The registration rate limit is per address, and an address is the one thing a signup
        // farm has plenty of. A free tier with no challenge is a free tier anyone can mint.
        ProductionSafetyValidator v = newValidator();
        setValid(v);
        ReflectionTestUtils.setField(v, "billingEnabled", true);
        ReflectionTestUtils.setField(v, "emailEnabled", true);
        ReflectionTestUtils.setField(v, "billingProvider", "stripe");
        ReflectionTestUtils.setField(v, "captchaSecretKey", "");

        IllegalStateException e = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(e.getMessage().contains("CAPTCHA_SECRET_KEY"));
    }
}
