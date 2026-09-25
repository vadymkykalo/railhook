package com.webhook.platform.api.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs from {@link PostConstruct} rather than on {@code ApplicationReadyEvent}, which fires
 * after the connector is already serving traffic with the unsafe config.
 */
@Component
@Slf4j
public class ProductionSafetyValidator {

    private static final Set<String> PLACEHOLDER_SECRETS = Set.of(
            "dev_encryption_key_32_chars_min",
            "dev_encryption_salt_16_chars",
            "dev_jwt_secret_key_32_chars_minimum",
            "development_master_key_32_chars",
            "changeme",
            "secret",
            "password"
    );

    // Exact values shipped in .env.dist: the operator copied it and never rotated.
    private static final Map<String, String> SHIPPED_DEFAULTS = Map.ofEntries(
            Map.entry("JWT_SECRET", "dev_jwt_secret_key_32_chars_minimum"),
            Map.entry("WEBHOOK_ENCRYPTION_KEY", "dev_encryption_key_32_chars_min!"),
            Map.entry("WEBHOOK_ENCRYPTION_SALT", "dev_encryption_salt_16_chars"),
            Map.entry("DB_PASSWORD", "webhook_dev_pass_12345"),
            Map.entry("REDIS_PASSWORD", "webhook_redis_pass")
    );

    // Real random secrets land well over 100 bits; this rejects repeated characters and words.
    private static final double MIN_SECRET_ENTROPY_BITS = 40.0;

    @Value("${APP_ENV:development}")
    private String appEnv;

    @Value("${webhook.encryption-key}")
    private String encryptionKey;

    @Value("${webhook.encryption-salt}")
    private String encryptionSalt;

    @Value("${jwt.secret:#{null}}")
    private String jwtSecret;

    @Value("${DB_PASSWORD:}")
    private String dbPassword;

    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    @Value("${cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Value("${webhook.url-validation.allow-private-ips:false}")
    private boolean allowPrivateIps;

    @Value("${swagger.enabled:true}")
    private boolean swaggerEnabled;

    @Value("${billing.enabled:false}")
    private boolean billingEnabled;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${captcha.secret-key:}")
    private String captchaSecretKey;

    @PostConstruct
    public void validateProductionConfig() {
        if (!"production".equalsIgnoreCase(appEnv)) {
            log.info("APP_ENV={} — skipping production safety checks", appEnv);
            return;
        }

        log.info("APP_ENV=production — running production safety checks...");
        List<String> violations = new ArrayList<>();

        validateSecret(violations, "WEBHOOK_ENCRYPTION_KEY", "WEBHOOK_ENCRYPTION_KEY", encryptionKey);
        validateSecret(violations, "WEBHOOK_ENCRYPTION_SALT", "WEBHOOK_ENCRYPTION_SALT", encryptionSalt);
        validateSecret(violations, "JWT_SECRET", "JWT_SECRET", jwtSecret);
        // POSTGRES_PASSWORD never reaches this container; DB_PASSWORD ships the same default.
        validateSecret(violations, "POSTGRES_PASSWORD (checked via DB_PASSWORD)", "DB_PASSWORD", dbPassword);
        validateSecret(violations, "REDIS_PASSWORD", "REDIS_PASSWORD", redisPassword);

        if (allowPrivateIps) {
            violations.add("WEBHOOK_ALLOW_PRIVATE_IPS=true — must be false in production (SSRF risk)");
        }
        if (swaggerEnabled) {
            violations.add("SWAGGER_ENABLED=true — should be false in production (info disclosure)");
        }
        validateHostedMode(violations);

        if (corsAllowedOrigins != null && !corsAllowedOrigins.isBlank()) {
            String lower = corsAllowedOrigins.toLowerCase();
            if (lower.contains("localhost") || lower.contains("127.0.0.1")) {
                violations.add("CORS_ALLOWED_ORIGINS still references localhost/127.0.0.1 ("
                        + corsAllowedOrigins + ") — set it to your real origin(s) for production");
            }
        }

        if (!violations.isEmpty()) {
            String message = "PRODUCTION SAFETY CHECK FAILED:\n  - " + String.join("\n  - ", violations);
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Production safety checks passed");
    }

    /**
     * BILLING_ENABLED is the only thing that marks a hosted deployment, and open registration
     * there needs mail (otherwise accounts are verified on the spot) and a CAPTCHA (the rate
     * limit is per address). A payment provider is not required: with noop it runs the free
     * plan only.
     */
    private void validateHostedMode(List<String> violations) {
        if (!billingEnabled) {
            return;
        }
        if (!emailEnabled) {
            violations.add("BILLING_ENABLED=true with EMAIL_ENABLED=false — registration would mark "
                    + "every account verified without sending anything, so a paid tier sits behind "
                    + "an address nobody proved they own");
        }
        if (captchaSecretKey == null || captchaSecretKey.isBlank()) {
            violations.add("BILLING_ENABLED=true with no CAPTCHA_SECRET_KEY — registration is then "
                    + "rate-limited per address and nothing else, which a signup farm distributes "
                    + "around; a free tier with no challenge is a free tier anyone can mint");
        }
    }

    /**
     * A blank value is skipped: docker-compose already refuses to start without a required var.
     */
    private void validateSecret(List<String> violations, String displayName, String shippedDefaultsKey, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (isPlaceholder(value)) {
            violations.add(displayName + " is a placeholder/dev default — must be changed for production");
            return;
        }
        String shippedDefault = SHIPPED_DEFAULTS.get(shippedDefaultsKey);
        if (shippedDefault != null && shippedDefault.equals(value)) {
            violations.add(displayName + " is unchanged from the .env.dist shipped default — must be changed for production");
            return;
        }
        double entropyBits = estimateEntropyBits(value);
        if (entropyBits < MIN_SECRET_ENTROPY_BITS) {
            violations.add(String.format(java.util.Locale.ROOT,
                    "%s has too little entropy (~%.1f bits) to be a real secret — must be changed for production",
                    displayName, entropyBits));
        }
    }

    private boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String lower = value.toLowerCase().trim();
        return PLACEHOLDER_SECRETS.stream().anyMatch(lower::contains);
    }

    /** Frequency-based Shannon entropy, per-character bits times length. */
    private static double estimateEntropyBits(String value) {
        Map<Character, Integer> frequency = new HashMap<>();
        for (char c : value.toCharArray()) {
            frequency.merge(c, 1, Integer::sum);
        }
        int length = value.length();
        double bitsPerChar = 0.0;
        for (int count : frequency.values()) {
            double p = (double) count / length;
            bitsPerChar -= p * (Math.log(p) / Math.log(2));
        }
        return bitsPerChar * length;
    }
}
