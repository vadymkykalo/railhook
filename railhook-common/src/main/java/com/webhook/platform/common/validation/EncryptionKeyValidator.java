package com.webhook.platform.common.validation;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Slf4j
public class EncryptionKeyValidator {

    private static final Set<String> INSECURE_KEYS = Set.of(
        "development_master_key_32_chars",
        "test_key",
        "changeme",
        "default_key",
        "12345678901234567890123456789012"
    );
    
    private static final int MINIMUM_KEY_LENGTH = 32;

    @Value("${webhook.encryption-key}")
    private String encryptionKey;

    @PostConstruct
    public void validateEncryptionKey() {
        if (encryptionKey == null || encryptionKey.trim().isEmpty()) {
            throw new IllegalStateException(
                "WEBHOOK_ENCRYPTION_KEY is not set. Generate a secure key using: openssl rand -base64 32"
            );
        }

        if (encryptionKey.length() < MINIMUM_KEY_LENGTH) {
            throw new IllegalStateException(
                String.format("WEBHOOK_ENCRYPTION_KEY must be at least %d characters. Current length: %d", 
                    MINIMUM_KEY_LENGTH, encryptionKey.length())
            );
        }

        if (INSECURE_KEYS.contains(encryptionKey)) {
            throw new IllegalStateException(
                "WEBHOOK_ENCRYPTION_KEY is using a known insecure default value. " +
                "Generate a secure key using: openssl rand -base64 32"
            );
        }

        log.info("Encryption key validation passed (length: {} characters)", encryptionKey.length());
    }
}
