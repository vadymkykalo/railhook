package com.webhook.platform.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CryptoUtils — encryption key versioning")
class CryptoUtilsEncryptionTest {

    private static final String KEY = "test_master_key_32_chars_long_xx";
    private static final String SALT = "test_salt";

    @Test
    void encryptSecret_recordsTheKeyVersion_defaultingTo1() {
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("hello", KEY, SALT);

        assertEquals(1, data.getKeyVersion());
        assertFalse(data.getCiphertext().isBlank());
        assertFalse(data.getIv().isBlank());
        assertEquals(5, CryptoUtils.encryptSecret("hello", KEY, SALT, 5).getKeyVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"secret data", "", "Привіт 🔐 世界"})
    void encryptDecrypt_roundTrip(String plaintext) {
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret(plaintext, KEY, SALT, 3);

        assertEquals(plaintext, CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, SALT));
    }

    @Test
    void differentKeys_cannotDecryptEachOther() {
        String otherKey = "other_key_32_chars_long_pad_xxxx";

        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("secret", KEY, SALT);

        assertThrows(RuntimeException.class, () ->
                CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), otherKey, SALT));
    }

    @Test
    void differentSalts_cannotDecryptEachOther() {
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("secret", KEY, SALT);

        assertThrows(RuntimeException.class, () ->
                CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, "different_salt"));
    }

    @Test
    void encrypt_sameData_producesDifferentCiphertext() {
        CryptoUtils.EncryptedData data1 = CryptoUtils.encryptSecret("same", KEY, SALT);
        CryptoUtils.EncryptedData data2 = CryptoUtils.encryptSecret("same", KEY, SALT);

        assertNotEquals(data1.getCiphertext(), data2.getCiphertext());
        assertNotEquals(data1.getIv(), data2.getIv());
    }

    // Re-deriving PBKDF2 on every decrypt let an unauthenticated /ingress caller burn CPU per request.
    @Test
    @DisplayName("a derived key is reused, not recomputed, for the same master key and salt")
    void derivationIsNotRepeated() {
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("payload", KEY, SALT);
        CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, SALT);

        Instant start = Instant.now();
        for (int i = 0; i < 200; i++) {
            assertEquals("payload",
                    CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, SALT));
        }
        Duration elapsed = Duration.between(start, Instant.now());

        assertTrue(elapsed.toMillis() < 2_000,
                "200 decrypts took " + elapsed.toMillis() + "ms — the key is being derived each time");
    }
}
