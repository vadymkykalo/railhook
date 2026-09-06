package com.webhook.platform.common.security;

import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages versioned encryption keys for zero-downtime key rotation.
 *
 * Configuration:
 * <pre>
 *   # Single key (backward-compatible, treated as version 1):
 *   webhook.encryption-key=my_secret_key
 *
 *   # Multi-key for rotation (format: "version:key,version:key"):
 *   webhook.encryption-keys=1:old_key_here,2:new_key_here
 *   webhook.encryption-key-active-version=2
 * </pre>
 *
 * During rotation:
 * 1. Add new key as version N+1 to webhook.encryption-keys
 * 2. Set webhook.encryption-key-active-version=N+1
 * 3. Deploy — new encryptions use version N+1, old data still decrypts with version N
 * 4. Run re-encryption (POST /api/v1/admin/encryption/rotate)
 * 5. Once all rows migrated, remove old key from config
 */
@Component
@Slf4j
public class EncryptionKeyRegistry {

    @Value("${webhook.encryption-key:}")
    private String singleKey;

    @Value("${webhook.encryption-keys:}")
    private String multiKeys;

    @Value("${webhook.encryption-key-active-version:0}")
    private int configuredActiveVersion;

    @Value("${webhook.encryption-salt}")
    private String salt;

    private Map<Integer, String> keyMap;
    private int activeVersion;

    @PostConstruct
    void init() {
        Map<Integer, String> keys = new LinkedHashMap<>();

        if (multiKeys != null && !multiKeys.isBlank()) {
            for (String entry : multiKeys.split(",")) {
                entry = entry.trim();
                if (entry.isEmpty()) continue;
                int colonIdx = entry.indexOf(':');
                if (colonIdx <= 0 || colonIdx == entry.length() - 1) {
                    throw new IllegalStateException(
                            "Invalid encryption key entry: '" + entry + "'. Expected format: 'version:key'");
                }
                int version = Integer.parseInt(entry.substring(0, colonIdx).trim());
                String key = entry.substring(colonIdx + 1).trim();
                if (key.isEmpty()) {
                    throw new IllegalStateException("Empty key for version " + version);
                }
                keys.put(version, key);
            }

            if (configuredActiveVersion > 0) {
                activeVersion = configuredActiveVersion;
            } else {
                activeVersion = keys.keySet().stream().max(Integer::compareTo).orElse(1);
            }

            if (!keys.containsKey(activeVersion)) {
                throw new IllegalStateException(
                        "Active encryption key version " + activeVersion + " not found in webhook.encryption-keys");
            }

        } else if (singleKey != null && !singleKey.isBlank()) {
            keys.put(1, singleKey);
            activeVersion = 1;
        } else {
            throw new IllegalStateException(
                    "No encryption key configured. Set webhook.encryption-key or webhook.encryption-keys");
        }

        this.keyMap = Collections.unmodifiableMap(keys);
        log.info("Encryption key registry initialized: {} key version(s), active version={}",
                keyMap.size(), activeVersion);
    }

    /** Returns the active version number for new encryptions. */
    public int getActiveVersion() {
        return activeVersion;
    }

    /** Returns the master key for the active version. */
    public String getActiveKey() {
        return keyMap.get(activeVersion);
    }

    /** Returns the salt (shared across all versions). */
    public String getSalt() {
        return salt;
    }

    /** Returns the master key for a specific version, or null if not found. */
    public String getKey(int version) {
        return keyMap.get(version);
    }

    /** Returns true if the given version exists in the registry. */
    public boolean hasVersion(int version) {
        return keyMap.containsKey(version);
    }

    /** Returns all registered version numbers. */
    public java.util.Set<Integer> getVersions() {
        return keyMap.keySet();
    }

    /** Encrypt with the active key version. */
    public CryptoUtils.EncryptedData encrypt(String plaintext) {
        return CryptoUtils.encryptSecret(plaintext, getActiveKey(), salt, activeVersion);
    }

    /** Decrypt using a specific key version. Falls back to active if version <= 0. */
    public String decrypt(String ciphertext, String iv, int keyVersion) {
        int version = keyVersion > 0 ? keyVersion : activeVersion;
        String key = keyMap.get(version);
        if (key == null) {
            throw new RuntimeException("Encryption key version " + version +
                    " not found. Available versions: " + keyMap.keySet());
        }
        return CryptoUtils.decryptSecret(ciphertext, iv, key, salt);
    }

    /** Decrypt trying specified version first, then falling back to all versions. */
    public String decryptWithFallback(String ciphertext, String iv, int keyVersion) {

        if (keyVersion > 0 && keyMap.containsKey(keyVersion)) {
            try {
                return CryptoUtils.decryptSecret(ciphertext, iv, keyMap.get(keyVersion), salt);
            } catch (Exception e) {
                log.warn("Failed to decrypt with key version {}, trying fallback", keyVersion);
            }
        }

        for (int version : keyMap.keySet().stream().sorted(Collections.reverseOrder()).toList()) {
            if (version == keyVersion) continue; // already tried
            try {
                return CryptoUtils.decryptSecret(ciphertext, iv, keyMap.get(version), salt);
            } catch (Exception ignored) {
                // try next
            }
        }

        throw new RuntimeException("Failed to decrypt with any available key version. " +
                "Available versions: " + keyMap.keySet());
    }

    /** Check if data needs re-encryption (encrypted with non-active version). */
    public boolean needsReEncryption(int keyVersion) {
        return keyVersion != activeVersion;
    }
}
