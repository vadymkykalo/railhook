package com.webhook.platform.worker.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to run the worker on a development configuration. In production mode
 * (APP_ENV=production), placeholder secrets and unsafe settings fail startup rather than
 * being served.
 *
 * <p>Runs from {@link PostConstruct} rather than {@code ApplicationReadyEvent}. The api made
 * the same move because the later event fires after its connector is already bound; for the
 * worker the window is worse, because what is already running by then is the Kafka listeners.
 * A worker that starts on a placeholder encryption key and an SSRF guard turned off does not
 * merely sit there being reachable — it delivers webhooks. The check has to be ahead of that,
 * not alongside it.
 */
@Component
@Slf4j
public class ProductionSafetyValidator {

    private static final Set<String> PLACEHOLDER_SECRETS = Set.of(
            "dev_encryption_key_32_chars_min",
            "dev_encryption_salt_16_chars",
            "development_master_key_32_chars",
            "changeme",
            "secret",
            "password"
    );

    @Value("${APP_ENV:development}")
    private String appEnv;

    @Value("${webhook.encryption-key}")
    private String encryptionKey;

    @Value("${webhook.encryption-salt}")
    private String encryptionSalt;

    @Value("${webhook.url-validation.allow-private-ips:false}")
    private boolean allowPrivateIps;

    @Value("${spring.kafka.bootstrap-servers:}")
    private String kafkaBootstrapServers;

    @PostConstruct
    public void validateProductionConfig() {
        if (!"production".equalsIgnoreCase(appEnv)) {
            log.info("APP_ENV={} — skipping production safety checks", appEnv);
            return;
        }

        log.info("APP_ENV=production — running worker production safety checks...");
        List<String> violations = new ArrayList<>();

        if (isPlaceholder(encryptionKey)) {
            violations.add("WEBHOOK_ENCRYPTION_KEY is a placeholder/dev default — must be changed for production");
        }
        if (isPlaceholder(encryptionSalt)) {
            violations.add("WEBHOOK_ENCRYPTION_SALT is a placeholder/dev default — must be changed for production");
        }
        if (allowPrivateIps) {
            violations.add("WEBHOOK_ALLOW_PRIVATE_IPS=true — must be false in production (SSRF risk)");
        }
        if (kafkaBootstrapServers.isBlank() || kafkaBootstrapServers.contains("localhost")) {
            violations.add("KAFKA_BOOTSTRAP_SERVERS points to localhost — must use production broker in production");
        }

        if (!violations.isEmpty()) {
            String message = "WORKER PRODUCTION SAFETY CHECK FAILED:\n  - " + String.join("\n  - ", violations);
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Worker production safety checks passed");
    }

    private boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String lower = value.toLowerCase().trim();
        return PLACEHOLDER_SECRETS.stream().anyMatch(p -> lower.contains(p));
    }
}
