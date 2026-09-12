package com.webhook.platform.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CryptoUtils — encryption key versioning")
class CryptoUtilsEncryptionTest {

    private static final String KEY = "test_master_key_32_chars_long_xx";
    private static final String SALT = "test_salt";

    @Test
    void encryptSecret_defaultVersion_is1() {
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("hello", KEY, SALT);

        assertEquals(1, data.getKeyVersion());
        assertFalse(data.getCiphertext().isBlank());
        assertFalse(data.getIv().isBlank());
    }

    @Test
    void encryptSecret_explicitVersion_stored() {
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("hello", KEY, SALT, 5);

        assertEquals(5, data.getKeyVersion());
    }

    @Test
    void encryptDecrypt_roundTrip() {
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("secret data", KEY, SALT, 3);

        String decrypted = CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, SALT);

        assertEquals("secret data", decrypted);
    }

    @Test
    void encryptedData_twoArgConstructor_defaultsToVersion1() {
        CryptoUtils.EncryptedData data = new CryptoUtils.EncryptedData("cipher", "iv");

        assertEquals(1, data.getKeyVersion());
        assertEquals("cipher", data.getCiphertext());
        assertEquals("iv", data.getIv());
    }

    @Test
    void encryptedData_threeArgConstructor() {
        CryptoUtils.EncryptedData data = new CryptoUtils.EncryptedData("cipher", "iv", 7);

        assertEquals(7, data.getKeyVersion());
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

    @Test
    void encrypt_emptyString_roundTrips() {
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("", KEY, SALT);

        String decrypted = CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, SALT);
        assertEquals("", decrypted);
    }

    @Test
    void encrypt_unicodeContent_roundTrips() {
        String unicode = "\u041f\u0440\u0438\u0432\u0456\u0442 \uD83D\uDD10 \u4e16\u754c";
        CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret(unicode, KEY, SALT, 2);

        String decrypted = CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, SALT);
        assertEquals(unicode, decrypted);
    }

    /**
     * The key derivation, and what it costs.
     *
     * <p>PBKDF2 at 65,536 iterations is deliberately expensive — that is what it is for. What it
     * is not for is running on every single encrypt and decrypt, which is what happened: the salt
     * is one process-wide value, so the derived key is the same bytes every time, recomputed from
     * scratch thousands of times a second.
     *
     * <p>That made it a workload anyone could book. {@code /ingress/{token}} decrypts the source's
     * HMAC secret to check a signature, so the derivation runs <em>before</em> the request is known
     * to be genuine — an unauthenticated caller who knows the token spends tens of milliseconds of
     * CPU per request, bounded only by the per-source rate limit.
     */
    @Nested
    @DisplayName("key derivation")
    class KeyDerivation {

        @Test
        @DisplayName("a derived key is reused, not recomputed, for the same master key and salt")
        void derivationIsNotRepeated() {
            // Not a benchmark — a floor. Uncached, 200 derivations are 200 x ~30-50ms, so six
            // seconds at the optimistic end. The bound below sits an order of magnitude under
            // that and two above anything a cache hit can cost, so it cannot fail for being on
            // a slow machine, only for deriving again.
            CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("payload", KEY, SALT);
            CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, SALT); // warm

            Instant start = Instant.now();
            for (int i = 0; i < 200; i++) {
                assertEquals("payload",
                        CryptoUtils.decryptSecret(data.getCiphertext(), data.getIv(), KEY, SALT));
            }
            Duration elapsed = Duration.between(start, Instant.now());

            assertTrue(elapsed.toMillis() < 2_000,
                    "200 decrypts took " + elapsed.toMillis() + "ms — the key is being derived each time");
        }

        @Test
        @DisplayName("the cache is keyed by both halves, so a different master key cannot read it")
        void adifferentMasterKeyDerivesADifferentKey() {
            CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("payload", KEY, SALT);

            assertThrows(RuntimeException.class, () -> CryptoUtils.decryptSecret(
                    data.getCiphertext(), data.getIv(), "another_master_key_32_chars_xxx", SALT));
        }

        @Test
        @DisplayName("and a different salt likewise")
        void aDifferentSaltDerivesADifferentKey() {
            CryptoUtils.EncryptedData data = CryptoUtils.encryptSecret("payload", KEY, SALT);

            assertThrows(RuntimeException.class, () -> CryptoUtils.decryptSecret(
                    data.getCiphertext(), data.getIv(), KEY, "a_different_salt"));
        }
    }
}
