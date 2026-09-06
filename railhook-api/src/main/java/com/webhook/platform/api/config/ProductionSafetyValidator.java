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
 * Validates that dangerous development defaults are not used in production.
 * In production mode (APP_ENV=production), placeholder secrets and unsafe settings
 * cause a startup failure to prevent accidental misconfigurations.
 *
 * Runs from {@link PostConstruct} rather than {@code ApplicationReadyEvent}:
 * the latter fires after the embedded connector is already bound and serving traffic,
 * leaving a live window where an insecure config is reachable before the check throws.
 * {@link SecurityConfigValidator} already uses this pattern; this class now matches it.
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

    // The exact values .env.dist ships for each secret. A production deployment that
    // still has one of these means the operator copied .env.dist -> .env and never
    // rotated the secret -- a fixed substring denylist alone lets values like
    // "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" through, so this is checked in addition to it.
    private static final Map<String, String> SHIPPED_DEFAULTS = Map.ofEntries(
            Map.entry("JWT_SECRET", "dev_jwt_secret_key_32_chars_minimum"),
            Map.entry("WEBHOOK_ENCRYPTION_KEY", "dev_encryption_key_32_chars_min!"),
            Map.entry("WEBHOOK_ENCRYPTION_SALT", "dev_encryption_salt_16_chars"),
            Map.entry("DB_PASSWORD", "webhook_dev_pass_12345"),
            Map.entry("REDIS_PASSWORD", "webhook_redis_pass")
    );

    // Floor for the Shannon-entropy estimate (bits, frequency-based) of a secret value.
    // Chosen to comfortably clear any real generateSecureToken()/openssl-rand style
    // secret (which land well over 100 bits) while rejecting repeated characters,
    // short dictionary words, and other low-randomness placeholders.
    private static final double MIN_SECRET_ENTROPY_BITS = 40.0;

    @Value("${APP_ENV:development}")
    private String appEnv;

    @Value("${webhook.encryption-key}")
    private String encryptionKey;

    @Value("${webhook.encryption-salt}")
    private String encryptionSalt;

    @Value("${jwt.secret:#{null}}")
    private String jwtSecret;

    // Bound directly to the raw env vars rather than their Spring-mapped properties:
    // these are read purely to gate startup, so there's no need to introduce (or
    // depend on) an application.yml property for each one.
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

    // The hosted-mode pair. Neither is unsafe on its own; together, one on and the other off
    // is a service that bills nobody and verifies nobody while believing it does both.
    @Value("${billing.enabled:false}")
    private boolean billingEnabled;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${billing.default-provider:noop}")
    private String billingProvider;

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
        // The app authenticates to Postgres with DB_PASSWORD; POSTGRES_PASSWORD (which sets
        // the DB's own bootstrap password in docker-compose) isn't forwarded into the api
        // container at all. Both ship the same default in .env.dist, so validating the one
        // the app actually receives covers the case the task calls out without adding a new
        // secret to the api container's environment.
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
     * Checks the settings that only make sense together once the platform is charging strangers.
     *
     * <p>{@code BILLING_ENABLED=true} is the whole of what separates a hosted deployment from a
     * self-hosted one — there is no separate build, no profile, no licence key. Which means the
     * hosted deployment is one unset variable away from being an open, unbilled, unverified
     * multi-tenant service that starts up perfectly happily and says nothing.
     *
     * <p>Two things it must not be missing:
     *
     * <ul>
     *   <li>Mail. Registration marks an account verified when no mail can be sent, because a
     *       token nobody receives proves nothing — correct for self-hosting, and on open
     *       registration it means every account is verified by assertion.</li>
     *   <li>A payment provider. {@code noop} accepts every plan change and charges for none, so
     *       billing is "enabled" and free.</li>
     *   <li>A CAPTCHA. The registration rate limit is per address, which is the one thing a
     *       signup farm has plenty of.</li>
     * </ul>
     *
     * <p>Nothing here fires for a self-hosted deployment: with billing off, which is the shipped
     * default, this method has nothing to say.
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
        if (billingProvider == null || billingProvider.isBlank() || "noop".equalsIgnoreCase(billingProvider)) {
            violations.add("BILLING_ENABLED=true with BILLING_DEFAULT_PROVIDER=" + billingProvider
                    + " — the no-op provider accepts every plan change and charges for none, so plans "
                    + "would be enforced and free");
        }
        if (captchaSecretKey == null || captchaSecretKey.isBlank()) {
            violations.add("BILLING_ENABLED=true with no CAPTCHA_SECRET_KEY — registration is then "
                    + "rate-limited per address and nothing else, which a signup farm distributes "
                    + "around; a free tier with no challenge is a free tier anyone can mint");
        }
    }

    /**
     * Rejects a secret if it's blank (nothing to check — a required var missing
     * entirely is already enforced by docker-compose's {@code ${VAR:?must be set}}
     * guard before the JVM even starts), a known placeholder substring, exactly the
     * value shipped in {@code .env.dist}, or below the entropy floor.
     *
     * @param displayName name used in the violation message (may differ from
     *                     {@code shippedDefaultsKey} when the checked variable is an
     *                     alias for the one .env.dist documents)
     * @param shippedDefaultsKey key into {@link #SHIPPED_DEFAULTS}
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

    /**
     * Frequency-based Shannon entropy of the value, in total bits (per-character
     * entropy times length). Not a substitute for a real randomness source, but
     * enough to reject repeated characters, short/simple values, and other
     * obviously-not-random placeholders.
     */
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
