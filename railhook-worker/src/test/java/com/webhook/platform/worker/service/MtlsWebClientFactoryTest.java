package com.webhook.platform.worker.service;

import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import com.webhook.platform.worker.domain.entity.Endpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MtlsWebClientFactory Tests")
class MtlsWebClientFactoryTest {

    private static final String ENCRYPTION_KEY = "test-encryption-key-for-mtls-32c";
    private static final String ENCRYPTION_SALT = "test-salt-value";
    private EncryptionKeyRegistry encryptionKeyRegistry;

    // Pre-generated self-signed RSA 2048 cert + PKCS8 key (via keytool+openssl)
    private static final String TEST_CERT_PEM = "-----BEGIN CERTIFICATE-----\n" +
            "MIICwjCCAaqgAwIBAgIJAJzzY03WpClSMA0GCSqGSIb3DQEBDAUAMA8xDTALBgNV\n" +
            "BAMTBFRlc3QwHhcNMjYwMjI4MTEzOTM1WhcNMjcwMjI4MTEzOTM1WjAPMQ0wCwYD\n" +
            "VQQDEwRUZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkmfdz+jm\n" +
            "ZR5Osxsf/bXN1Ff8Ig1S0e/juWdE7ZyC5K5UsNY8varsy5aeQKcVW6ksKZ63s7uA\n" +
            "zZttzv4x/cXilcqqMt9JGF55/gDSw67nahxrp7fTyCoz26K6bma4c1N1e3DLPo+h\n" +
            "SWuOUXPFUi9Su8ftqDJ9aqX80aKm5GHxKlox90mOWlhl2/18idLjl2fkcpr6Gsti\n" +
            "UzpWMh3sqmXBD0aoEHCsdbgICfGodf+YOsyXh1ujYGpJ9ob4j4FnJs4P8GpXDlST\n" +
            "z4N5F7nph5SKoBO9t7To54YVvAjr4FX4amRF9BJfMuKs7afl8hxHMEGiy2v1U/Ut\n" +
            "nfyVsnplQSqJkwIDAQABoyEwHzAdBgNVHQ4EFgQUWM9lhcyGyBP2cI7D6V/KuZmb\n" +
            "KlcwDQYJKoZIhvcNAQEMBQADggEBAILDzdQqRxLfKz+ghB/3+oey0uURm6Xz5Kav\n" +
            "40CqqzXcmtpguzU7EHKB+wtphlD8M93GaDzWZheEoaQu5liPuq3lmVuZ72tX3z7Z\n" +
            "DtTQjR0ZtJwVXpQkWeBLSQx/YG1ft7necPfKyRWPHeBxQUBK1xRH+nJs1vMWODNY\n" +
            "psXYP8q8ZcAq8DcAfaB/ncHBv7V7ctm6ZK1EWXCkVdik7ytXm1gvGwH3j6ozdVpq\n" +
            "dMgaiBeVjVHfo+z4z4GI9aPeS/QD7F90v22zp89HVA1DlqJ/QHYpfoHDmB8fVdJ2\n" +
            "oIrVDc9SVe5mDKRclVJNdJd6ASXEOzvSW//sI/yPH+MUICNDess=\n" +
            "-----END CERTIFICATE-----";

    private static final String TEST_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n" +
            "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCSZ93P6OZlHk6z\n" +
            "Gx/9tc3UV/wiDVLR7+O5Z0TtnILkrlSw1jy9quzLlp5ApxVbqSwpnrezu4DNm23O\n" +
            "/jH9xeKVyqoy30kYXnn+ANLDrudqHGunt9PIKjPborpuZrhzU3V7cMs+j6FJa45R\n" +
            "c8VSL1K7x+2oMn1qpfzRoqbkYfEqWjH3SY5aWGXb/XyJ0uOXZ+Rymvoay2JTOlYy\n" +
            "HeyqZcEPRqgQcKx1uAgJ8ah1/5g6zJeHW6Ngakn2hviPgWcmzg/walcOVJPPg3kX\n" +
            "uemHlIqgE723tOjnhhW8COvgVfhqZEX0El8y4qztp+XyHEcwQaLLa/VT9S2d/JWy\n" +
            "emVBKomTAgMBAAECggEAAObaNef1q1UcH6b2HnuahfP4yJjpFAP0lMrEYOugNBpi\n" +
            "ySgN/bkUy6Lk3KRq0ZgMKUF3WN25ywpptbxZFdAR4jb6Wg/dWmS9PvRFrWY2ugTs\n" +
            "y36qs5vsRS5jvumrudd0wiA4EELq4mc4MYY+BpCQuOQKsbOF6ZHrfxBlE25gVuYZ\n" +
            "mgAcO6eVhJ/TEReqTRVs06zNFC7Qx3huM4f+8xOuImEX6RrljZHqGNuT1j/SvXpC\n" +
            "bJQ/lOCLcG5hl3/dM9K6c0uWbSvG7ZkAT5gxSKXjPqoosRRe8Th+iGkOZxkSFaKo\n" +
            "EFKB3zePId2NrF3XxmCkjYwny6BNF2RhmHwX4IHHgQKBgQC31rcpoADcVUrSk6ju\n" +
            "LrSYM3YqLOqmY8gZSbYepJOx85RQ5LXXv1FqmFzbOSGnTqeYt4rX/I0pLWs8e4Ae\n" +
            "NIJhADVyq/PND+0tFtBckWthbp6Gd2T5En2jNJb+AYTFRccpqbamr6SzJ+TKSf+m\n" +
            "ArEEeItUIcH6vXmXSOzKYiA2UwKBgQDL36UPQwT7wk48TMttN9SLLdV7zdAO1Rmo\n" +
            "9IqXb8DNU5JyhsCLRhCTpw8uluz09ahVlhnV4VpI9050faAFSW88WNLFChjWycdm\n" +
            "DNqeZas8ts3GozSnKOQ+X/aXvA87/yZJHqj35Q5my3UvJ+PiyEDBS3PBQVv3PyDp\n" +
            "/fBPBbt3wQKBgFQT4j8qS1p5s7etCqSsPbIiTxeo5URl/Dz8hktrb5UCVsHMaBId\n" +
            "EMpUlps4fNi800+4GcsAWTsM56+IuCaYU0yzwL4KQH13nDxz46WCaH7uDZhoAIkd\n" +
            "WNKMmcXfwe5LJHQ8hymiyQua4jtWLpKpRnZJ/0biDYp0n6h+FLXWvO67AoGAbkgV\n" +
            "JnNPb8xEu9OiuvrXa04ozCah5FQb5Ewb6B0Ygzkw3+jKoMwOzYAx8zbLCRsVqu5e\n" +
            "HVcgmpXEh7ko5ZM6q780jEeQ9icCSM6tN7+xaE9OcqP4KHAzPxZz8tJUv1Se5jDC\n" +
            "oA30w2BNjGuclyFR/f2NqT1svQsWB91Ir8ZfCUECgYAZLegrREpzDH2Ob6WhgIqh\n" +
            "Q+thX64sEH78F3tdBx8HhQIFIDxs7mrC5xVge2a+QUe8Ffejn45+zkYnNDXYwpDa\n" +
            "4Q+WAn4YjINZhH8gFdDsbgCwYQ7WdwqUK4KBlpGlD2kMwYQeUx4hGDRwN9qkTbQS\n" +
            "WtTdb+rkamGAT1V4fUtmEw==\n" +
            "-----END PRIVATE KEY-----";

    private MtlsWebClientFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        encryptionKeyRegistry = createTestRegistry(ENCRYPTION_KEY, ENCRYPTION_SALT);
        factory = new MtlsWebClientFactory(encryptionKeyRegistry, true, java.util.List.of(), WebClient.builder(), ConnectionProvider.newConnection());
    }

    private static EncryptionKeyRegistry createTestRegistry(String key, String salt) throws Exception {
        EncryptionKeyRegistry registry = new EncryptionKeyRegistry();
        setField(registry, "singleKey", key);
        setField(registry, "multiKeys", "");
        setField(registry, "configuredActiveVersion", 0);
        setField(registry, "salt", salt);
        var initMethod = registry.getClass().getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(registry);
        return registry;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    // -----------------------------------------------------------------------
    // mTLS disabled / null
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should return default WebClient when mTLS is disabled")
    void shouldReturnDefaultClientWhenMtlsDisabled() {
        Endpoint endpoint = createEndpoint(false);
        assertNotNull(factory.getWebClient(endpoint));
    }

    @Test
    @DisplayName("Should return default WebClient when mtlsEnabled is null")
    void shouldReturnDefaultClientWhenMtlsNull() {
        Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID())
                .mtlsEnabled(null)
                .build();
        assertNotNull(factory.getWebClient(endpoint));
    }

    // -----------------------------------------------------------------------
    // mTLS enabled — WebClient creation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should create mTLS WebClient with valid RSA cert and key")
    void shouldCreateMtlsClientWithRsaCert() {
        Endpoint endpoint = createMtlsEndpoint(TEST_CERT_PEM, TEST_KEY_PEM, null);
        WebClient result = factory.getWebClient(endpoint);
        assertNotNull(result, "mTLS WebClient should be created successfully");
    }

    @Test
    @DisplayName("Should create mTLS WebClient with CA cert")
    void shouldCreateMtlsClientWithCaCert() {
        // Use same cert as CA for testing
        Endpoint endpoint = createMtlsEndpoint(TEST_CERT_PEM, TEST_KEY_PEM, TEST_CERT_PEM);
        WebClient result = factory.getWebClient(endpoint);
        assertNotNull(result, "mTLS WebClient with CA cert should be created");
    }

    // -----------------------------------------------------------------------
    // Caching
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should cache mTLS WebClient for same endpoint")
    void shouldCacheMtlsClient() {
        Endpoint endpoint = createMtlsEndpoint(TEST_CERT_PEM, TEST_KEY_PEM, null);

        WebClient first = factory.getWebClient(endpoint);
        WebClient second = factory.getWebClient(endpoint);

        assertSame(first, second, "Should return cached WebClient instance");
    }

    @Test
    @DisplayName("Should invalidate cache when endpoint updatedAt changes")
    void shouldInvalidateCacheOnUpdate() {
        UUID endpointId = UUID.randomUUID();
        Instant original = Instant.now().minusSeconds(60);

        Endpoint endpoint = createMtlsEndpoint(TEST_CERT_PEM, TEST_KEY_PEM, null);
        endpoint.setId(endpointId);
        endpoint.setUpdatedAt(original);

        WebClient first = factory.getWebClient(endpoint);

        endpoint.setUpdatedAt(Instant.now());
        WebClient second = factory.getWebClient(endpoint);

        assertNotSame(first, second, "Should create new WebClient after update");
    }

    @Test
    @DisplayName("Should invalidate cache manually via invalidateCache()")
    void shouldInvalidateCacheManually() {
        Endpoint endpoint = createMtlsEndpoint(TEST_CERT_PEM, TEST_KEY_PEM, null);

        WebClient first = factory.getWebClient(endpoint);
        factory.invalidateCache(endpoint.getId());
        WebClient second = factory.getWebClient(endpoint);

        assertNotSame(first, second, "Should create new WebClient after manual invalidation");
    }

    // -----------------------------------------------------------------------
    // Error cases
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should throw RuntimeException on invalid cert")
    void shouldThrowOnInvalidCert() {
        CryptoUtils.EncryptedData enc = CryptoUtils.encryptSecret("not-a-cert", ENCRYPTION_KEY, ENCRYPTION_SALT);
        CryptoUtils.EncryptedData encKey = CryptoUtils.encryptSecret("not-a-key", ENCRYPTION_KEY, ENCRYPTION_SALT);

        Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID())
                .mtlsEnabled(true)
                .clientCertEncrypted(enc.getCiphertext())
                .clientCertIv(enc.getIv())
                .clientKeyEncrypted(encKey.getCiphertext())
                .clientKeyIv(encKey.getIv())
                .updatedAt(Instant.now())
                .build();

        assertThrows(RuntimeException.class, () -> factory.getWebClient(endpoint),
                "Should throw on invalid certificate PEM");
    }

    @Test
    @DisplayName("Should throw on null encrypted cert data")
    void shouldThrowOnNullEncryptedCert() {
        Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID())
                .mtlsEnabled(true)
                .clientCertEncrypted(null)
                .clientCertIv(null)
                .clientKeyEncrypted(null)
                .clientKeyIv(null)
                .updatedAt(Instant.now())
                .build();

        assertThrows(RuntimeException.class, () -> factory.getWebClient(endpoint));
    }

    // -----------------------------------------------------------------------
    // CryptoUtils encrypt/decrypt round-trip for mTLS data
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should encrypt and decrypt cert PEM round-trip")
    void shouldEncryptDecryptCertRoundTrip() {
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(TEST_CERT_PEM, ENCRYPTION_KEY, ENCRYPTION_SALT);
        String decrypted = CryptoUtils.decryptSecret(
                encrypted.getCiphertext(), encrypted.getIv(), ENCRYPTION_KEY, ENCRYPTION_SALT);
        assertEquals(TEST_CERT_PEM, decrypted);
    }

    @Test
    @DisplayName("Should encrypt and decrypt key PEM round-trip")
    void shouldEncryptDecryptKeyRoundTrip() {
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(TEST_KEY_PEM, ENCRYPTION_KEY, ENCRYPTION_SALT);
        String decrypted = CryptoUtils.decryptSecret(
                encrypted.getCiphertext(), encrypted.getIv(), ENCRYPTION_KEY, ENCRYPTION_SALT);
        assertEquals(TEST_KEY_PEM, decrypted);
    }

    @Test
    @DisplayName("Different encryptions of same data should produce different ciphertext (random IV)")
    void shouldUseDifferentIvPerEncryption() {
        CryptoUtils.EncryptedData first = CryptoUtils.encryptSecret(TEST_CERT_PEM, ENCRYPTION_KEY, ENCRYPTION_SALT);
        CryptoUtils.EncryptedData second = CryptoUtils.encryptSecret(TEST_CERT_PEM, ENCRYPTION_KEY, ENCRYPTION_SALT);

        assertNotEquals(first.getIv(), second.getIv(), "IVs should differ per encryption");
        assertNotEquals(first.getCiphertext(), second.getCiphertext(), "Ciphertext should differ per encryption");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Endpoint createEndpoint(boolean mtlsEnabled) {
        return Endpoint.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .url("https://example.com/webhook")
                .mtlsEnabled(mtlsEnabled)
                .enabled(true)
                .secretEncrypted("dummy")
                .secretIv("dummy")
                .updatedAt(Instant.now())
                .build();
    }

    private Endpoint createMtlsEndpoint(String certPem, String keyPem, String caCertPem) {
        CryptoUtils.EncryptedData encCert = CryptoUtils.encryptSecret(certPem, ENCRYPTION_KEY, ENCRYPTION_SALT);
        CryptoUtils.EncryptedData encKey = CryptoUtils.encryptSecret(keyPem, ENCRYPTION_KEY, ENCRYPTION_SALT);

        return Endpoint.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .url("https://example.com/webhook")
                .mtlsEnabled(true)
                .clientCertEncrypted(encCert.getCiphertext())
                .clientCertIv(encCert.getIv())
                .clientKeyEncrypted(encKey.getCiphertext())
                .clientKeyIv(encKey.getIv())
                .caCert(caCertPem)
                .enabled(true)
                .secretEncrypted("dummy")
                .secretIv("dummy")
                .updatedAt(Instant.now())
                .build();
    }
}
