package com.webhook.platform.worker.config;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worker's half of the production gate. The api's equivalent has had tests since it was
 * written; this one had none, which is how it kept an {@code ApplicationReadyEvent} trigger
 * long after the api had moved off it.
 */
class ProductionSafetyValidatorTest {

    private static final String STRONG_KEY = "kQ2v9ZpL7xR4mN8sT1wY6bC3dF0gH5jK9nM2pQ7rS4t";
    private static final String STRONG_SALT = "hB6yN3wQ8vX1rT5mK9pL2sD7fG4jC0zA6eR3uY8iO1w";

    private ProductionSafetyValidator productionValidator() {
        ProductionSafetyValidator v = new ProductionSafetyValidator();
        ReflectionTestUtils.setField(v, "appEnv", "production");
        ReflectionTestUtils.setField(v, "encryptionKey", STRONG_KEY);
        ReflectionTestUtils.setField(v, "encryptionSalt", STRONG_SALT);
        ReflectionTestUtils.setField(v, "allowPrivateIps", false);
        ReflectionTestUtils.setField(v, "kafkaBootstrapServers", "kafka.internal:9092");
        return v;
    }

    @Test
    @DisplayName("the check runs before anything else does, not once the worker is already delivering")
    void runsFromPostConstruct() throws NoSuchMethodException {
        Method check = ProductionSafetyValidator.class.getMethod("validateProductionConfig");

        assertTrue(check.isAnnotationPresent(PostConstruct.class),
                "validateProductionConfig must run from @PostConstruct. On ApplicationReadyEvent the Kafka "
                        + "listeners have already started, so a worker configured with a placeholder encryption "
                        + "key and the SSRF guard off does not just sit there being wrong — it delivers webhooks "
                        + "before the check throws.");
    }

    @Test
    @DisplayName("a development environment is never checked")
    void developmentSkipsTheChecks() {
        ProductionSafetyValidator v = productionValidator();
        ReflectionTestUtils.setField(v, "appEnv", "development");
        ReflectionTestUtils.setField(v, "encryptionKey", "changeme");
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);

        assertDoesNotThrow(v::validateProductionConfig);
    }

    @Test
    @DisplayName("a valid production configuration starts")
    void validProductionConfigPasses() {
        assertDoesNotThrow(productionValidator()::validateProductionConfig);
    }

    @Test
    @DisplayName("a placeholder encryption key fails startup")
    void placeholderEncryptionKeyFails() {
        ProductionSafetyValidator v = productionValidator();
        ReflectionTestUtils.setField(v, "encryptionKey", "dev_encryption_key_32_chars_min");

        IllegalStateException e = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(e.getMessage().contains("WEBHOOK_ENCRYPTION_KEY"), e.getMessage());
    }

    @Test
    @DisplayName("a blank encryption salt fails startup")
    void blankEncryptionSaltFails() {
        ProductionSafetyValidator v = productionValidator();
        ReflectionTestUtils.setField(v, "encryptionSalt", "  ");

        assertThrows(IllegalStateException.class, v::validateProductionConfig);
    }

    @Test
    @DisplayName("the SSRF guard turned off fails startup")
    void privateIpsAllowedFails() {
        ProductionSafetyValidator v = productionValidator();
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);

        IllegalStateException e = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(e.getMessage().contains("WEBHOOK_ALLOW_PRIVATE_IPS"), e.getMessage());
    }

    @Test
    @DisplayName("a broker still pointing at localhost fails startup")
    void localhostBrokerFails() {
        ProductionSafetyValidator v = productionValidator();
        ReflectionTestUtils.setField(v, "kafkaBootstrapServers", "localhost:9092");

        IllegalStateException e = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(e.getMessage().contains("KAFKA_BOOTSTRAP_SERVERS"), e.getMessage());
    }

    @Test
    @DisplayName("every violation is reported at once, not one per restart")
    void allViolationsReportedTogether() {
        ProductionSafetyValidator v = productionValidator();
        ReflectionTestUtils.setField(v, "encryptionKey", "changeme");
        ReflectionTestUtils.setField(v, "allowPrivateIps", true);
        ReflectionTestUtils.setField(v, "kafkaBootstrapServers", "");

        IllegalStateException e = assertThrows(IllegalStateException.class, v::validateProductionConfig);
        assertTrue(e.getMessage().contains("WEBHOOK_ENCRYPTION_KEY"), e.getMessage());
        assertTrue(e.getMessage().contains("WEBHOOK_ALLOW_PRIVATE_IPS"), e.getMessage());
        assertTrue(e.getMessage().contains("KAFKA_BOOTSTRAP_SERVERS"), e.getMessage());
    }
}
