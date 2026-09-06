package com.webhook.platform.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
