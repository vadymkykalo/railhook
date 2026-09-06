package com.webhook.platform.common.security;

import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EncryptionKeyRegistry")
class EncryptionKeyRegistryTest {

    private static final String KEY_V1 = "old_master_key_32_chars_long_pad";
    private static final String KEY_V2 = "new_master_key_32_chars_long_pad";
    private static final String SALT = "test_salt_value";

    private static EncryptionKeyRegistry buildRegistry(String singleKey, String multiKeys,
                                                        int activeVersion, String salt) throws Exception {
        EncryptionKeyRegistry registry = new EncryptionKeyRegistry();
        setField(registry, "singleKey", singleKey);
        setField(registry, "multiKeys", multiKeys);
        setField(registry, "configuredActiveVersion", activeVersion);
        setField(registry, "salt", salt);
        var initMethod = registry.getClass().getDeclaredMethod("init");
        initMethod.setAccessible(true);
        try {
            initMethod.invoke(registry);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
        return registry;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    @Nested
    @DisplayName("Initialization")
    class Init {

        @Test
        void singleKey_treatedAsVersion1() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry(KEY_V1, "", 0, SALT);

            assertEquals(1, reg.getActiveVersion());
            assertEquals(KEY_V1, reg.getActiveKey());
            assertTrue(reg.getVersions().contains(1));
            assertEquals(1, reg.getVersions().size());
            assertEquals(SALT, reg.getSalt());
        }

        @Test
        void multiKeys_parsedCorrectly() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);

            assertEquals(2, reg.getActiveVersion());
            assertEquals(KEY_V2, reg.getActiveKey());
            assertEquals(Set.of(1, 2), reg.getVersions());
            assertEquals(KEY_V1, reg.getKey(1));
            assertEquals(KEY_V2, reg.getKey(2));
        }

        @Test
        void multiKeys_autoDetectsHighestVersion() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",3:" + KEY_V2, 0, SALT);

            assertEquals(3, reg.getActiveVersion());
            assertEquals(KEY_V2, reg.getActiveKey());
        }

        @Test
        void multiKeys_overridesSingleKey() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry(KEY_V1, "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);

            assertEquals(2, reg.getActiveVersion());
            assertEquals(2, reg.getVersions().size());
        }

        @Test
        void noKeys_throwsIllegalState() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> buildRegistry("", "", 0, SALT));
            assertTrue(ex.getMessage().contains("No encryption key configured"));
        }

        @Test
        void invalidMultiKeyFormat_throws() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> buildRegistry("", "badformat", 0, SALT));
            assertTrue(ex.getMessage().contains("Invalid encryption key entry"));
        }

        @Test
        void activeVersionNotInKeys_throws() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> buildRegistry("", "1:" + KEY_V1, 5, SALT));
            assertTrue(ex.getMessage().contains("not found in webhook.encryption-keys"));
        }

        @Test
        void emptyKeyValue_throws() {
            assertThrows(IllegalStateException.class,
                    () -> buildRegistry("", "1:", 0, SALT));
        }
    }

    @Nested
    @DisplayName("Encrypt and Decrypt")
    class EncryptDecrypt {

        @Test
        void encrypt_roundTrip_singleKey() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry(KEY_V1, "", 0, SALT);

            CryptoUtils.EncryptedData encrypted = reg.encrypt("hello world");

            assertEquals(1, encrypted.getKeyVersion());
            assertFalse(encrypted.getCiphertext().isBlank());
            assertFalse(encrypted.getIv().isBlank());

            String decrypted = reg.decrypt(encrypted.getCiphertext(), encrypted.getIv(), 1);
            assertEquals("hello world", decrypted);
        }

        @Test
        void encrypt_usesActiveVersion() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);

            CryptoUtils.EncryptedData encrypted = reg.encrypt("secret data");
            assertEquals(2, encrypted.getKeyVersion());

            String decrypted = reg.decrypt(encrypted.getCiphertext(), encrypted.getIv(), 2);
            assertEquals("secret data", decrypted);
        }

        @Test
        void decrypt_withOldKey_works() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);

            CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret("old data", KEY_V1, SALT, 1);

            String decrypted = reg.decrypt(encrypted.getCiphertext(), encrypted.getIv(), 1);
            assertEquals("old data", decrypted);
        }

        @Test
        void decrypt_wrongVersion_throws() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry(KEY_V1, "", 0, SALT);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> reg.decrypt("cipher", "iv", 99));
            assertTrue(ex.getMessage().contains("version 99 not found"));
        }

        @Test
        void decrypt_zeroVersion_fallsBackToActive() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry(KEY_V1, "", 0, SALT);

            CryptoUtils.EncryptedData encrypted = reg.encrypt("test");

            String decrypted = reg.decrypt(encrypted.getCiphertext(), encrypted.getIv(), 0);
            assertEquals("test", decrypted);
        }
    }

    @Nested
    @DisplayName("Decrypt with fallback")
    class DecryptWithFallback {

        @Test
        void fallback_decryptsWithCorrectVersion() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);

            CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret("data", KEY_V1, SALT, 1);

            String decrypted = reg.decryptWithFallback(encrypted.getCiphertext(), encrypted.getIv(), 1);
            assertEquals("data", decrypted);
        }

        @Test
        void fallback_triesOtherVersions_whenSpecifiedFails() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);

            // Encrypted with v2 but caller says version=1 (wrong metadata)
            CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret("data", KEY_V2, SALT, 2);

            String decrypted = reg.decryptWithFallback(encrypted.getCiphertext(), encrypted.getIv(), 1);
            assertEquals("data", decrypted);
        }

        @Test
        void fallback_triesAllVersions_whenVersionZero() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);

            CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret("data", KEY_V1, SALT, 1);

            String decrypted = reg.decryptWithFallback(encrypted.getCiphertext(), encrypted.getIv(), 0);
            assertEquals("data", decrypted);
        }

        @Test
        void fallback_allFail_throws() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry(KEY_V1, "", 0, SALT);

            // Encrypted with a completely different key
            CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret("data", KEY_V2, SALT, 1);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> reg.decryptWithFallback(encrypted.getCiphertext(), encrypted.getIv(), 1));
            assertTrue(ex.getMessage().contains("Failed to decrypt with any available key version"));
        }
    }

    @Nested
    @DisplayName("needsReEncryption")
    class NeedsReEncryption {

        @Test
        void sameVersion_noReEncryption() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);
            assertFalse(reg.needsReEncryption(2));
        }

        @Test
        void oldVersion_needsReEncryption() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);
            assertTrue(reg.needsReEncryption(1));
        }
    }

    @Nested
    @DisplayName("hasVersion")
    class HasVersion {

        @Test
        void existingVersion_returnsTrue() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);
            assertTrue(reg.hasVersion(1));
            assertTrue(reg.hasVersion(2));
        }

        @Test
        void nonExistingVersion_returnsFalse() throws Exception {
            EncryptionKeyRegistry reg = buildRegistry(KEY_V1, "", 0, SALT);
            assertFalse(reg.hasVersion(99));
        }
    }

    @Test
    @DisplayName("Full re-encryption round-trip: v1 -> v2")
    void reEncryption_roundTrip() throws Exception {
        EncryptionKeyRegistry reg = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);

        // Simulate old data encrypted with v1
        CryptoUtils.EncryptedData oldData = CryptoUtils.encryptSecret("sensitive", KEY_V1, SALT, 1);

        // Decrypt with v1 then re-encrypt with active (v2)
        String plaintext = reg.decryptWithFallback(oldData.getCiphertext(), oldData.getIv(), 1);
        CryptoUtils.EncryptedData newData = reg.encrypt(plaintext);

        assertEquals(2, newData.getKeyVersion());

        // Verify new data decrypts correctly with v2
        String decrypted = reg.decrypt(newData.getCiphertext(), newData.getIv(), 2);
        assertEquals("sensitive", decrypted);

        // Verify old data no longer decrypts with v2 key directly
        assertThrows(RuntimeException.class, () ->
                CryptoUtils.decryptSecret(oldData.getCiphertext(), oldData.getIv(), KEY_V2, SALT));
    }
}
