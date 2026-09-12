package com.webhook.platform.common.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

public class CryptoUtils {

    /**
     * Derived keys, kept because deriving them is the expensive part and the answer never changes.
     *
     * <p>PBKDF2 at {@link #PBKDF2_ITERATIONS} iterations is meant to cost something — that is the
     * whole point of it, applied once, when a key is established. It was being applied on every
     * encrypt and every decrypt instead, against a salt that is one process-wide configuration
     * value, so the same bytes were recomputed from scratch thousands of times a second for no
     * result that differed.
     *
     * <p>It also made the cost bookable by a stranger: the ingress path decrypts a source's HMAC
     * secret in order to check the signature, so the derivation ran before the request had been
     * shown to be genuine.
     *
     * <p>Bounded and expiring, though the live population is the handful of configured key
     * versions: a map that can only grow is a map that eventually matters. Holding derived keys
     * in memory adds no exposure — the master key they come from is already there.
     */
    private static final Cache<DerivationKey, SecretKey> DERIVED_KEYS = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    /** Both halves, kept apart: concatenating them lets one pair spell another. */
    private record DerivationKey(String masterKey, String salt) {
    }

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int AES_KEY_LENGTH_BITS = 256;

    public static String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash API key", e);
        }
    }

    public static EncryptedData encryptSecret(String plaintext, String masterKey, String salt) {
        return encryptSecret(plaintext, masterKey, salt, 1);
    }

    public static EncryptedData encryptSecret(String plaintext, String masterKey, String salt, int keyVersion) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            SecretKey key = deriveKey(masterKey, salt);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return new EncryptedData(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv),
                    keyVersion
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret", e);
        }
    }

    public static String decryptSecret(String ciphertext, String iv, String masterKey, String salt) {
        try {
            byte[] ciphertextBytes = Base64.getDecoder().decode(ciphertext);
            byte[] ivBytes = Base64.getDecoder().decode(iv);

            SecretKey key = deriveKey(masterKey, salt);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, ivBytes);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertextBytes);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt secret", e);
        }
    }

    public static String generateSecureToken(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static SecretKey deriveKey(String masterKey, String salt) {
        return DERIVED_KEYS.get(new DerivationKey(masterKey, salt), CryptoUtils::derive);
    }

    private static SecretKey derive(DerivationKey key) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(key.masterKey().toCharArray(),
                    key.salt().getBytes(StandardCharsets.UTF_8), PBKDF2_ITERATIONS, AES_KEY_LENGTH_BITS);
            SecretKey tmp = factory.generateSecret(spec);
            return new SecretKeySpec(tmp.getEncoded(), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive encryption key", e);
        }
    }

    public static class EncryptedData {
        private final String ciphertext;
        private final String iv;
        private final int keyVersion;

        public EncryptedData(String ciphertext, String iv) {
            this(ciphertext, iv, 1);
        }

        public EncryptedData(String ciphertext, String iv, int keyVersion) {
            this.ciphertext = ciphertext;
            this.iv = iv;
            this.keyVersion = keyVersion;
        }

        public String getCiphertext() {
            return ciphertext;
        }

        public String getIv() {
            return iv;
        }

        public int getKeyVersion() {
            return keyVersion;
        }
    }
}
