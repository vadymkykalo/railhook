package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import com.webhook.platform.common.util.WebhookSignatureUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Rotating a signing secret must not break the receiver.
 *
 * <p>Before the grace window, {@code rotateSecret} replaced the secret in place. From that
 * instant every delivery was signed with a key the customer had not deployed yet, so each
 * one failed their verification — a rotation was an outage they had to schedule. The columns
 * for a previous secret had existed since V001 and nothing ever wrote them;
 * {@code EntityMappingParityIntegrationTest} carried four exemptions saying exactly that.
 *
 * <p>These tests pin the half that lives in the api: that the retired secret is kept, that it
 * is kept in a form the worker can actually decrypt, and that rotating is still possible when
 * the current secret is not decryptable at all — which is the situation an operator rotates
 * to get out of.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EndpointService.rotateSecret — the rotation grace window")
class EndpointSecretRotationTest {

    private static final String MASTER_KEY = "master_key_32_chars_long_padding";
    private static final String SALT = "test_salt_value";
    private static final String BODY = "{\"id\":\"evt_1\"}";

    @Mock private EndpointRepository endpointRepository;
    @Mock private ProjectRepository projectRepository;

    private EncryptionKeyRegistry registry;
    private EndpointService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        registry = buildRegistry();
        service = new EndpointService(
                endpointRepository, projectRepository, WebClient.builder(), registry,
                true, Collections.emptyList(), false);

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).name("p").build()));
        when(endpointRepository.saveAndFlush(any(Endpoint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("keeps the retired secret, and a receiver still holding it keeps verifying")
    void retiredSecretStaysValid() {
        String original = "the_secret_the_customer_deployed";
        Endpoint endpoint = endpointWithSecret(original);
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        Instant before = Instant.now();
        String newSecret = service.rotateSecret(endpointId).getSecret();

        assertThat(endpoint.getSecretRotatedAt())
                .as("the window has to start somewhere")
                .isNotNull()
                .isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(endpoint.getSecretPreviousEncrypted()).isNotNull();
        assertThat(endpoint.getSecretPreviousIv()).isNotNull();

        String kept = registry.decryptWithFallback(
                endpoint.getSecretPreviousEncrypted(),
                endpoint.getSecretPreviousIv(),
                endpoint.getEncryptionKeyVersion());
        assertThat(kept).isEqualTo(original);

        /* The end-to-end promise, assembled from both halves: the header the worker builds
           from these two secrets verifies for a receiver on either one. */
        long ts = System.currentTimeMillis();
        String header = WebhookSignatureUtils.buildSignatureHeader(newSecret, kept, ts, BODY);
        assertThat(WebhookSignatureUtils.verifySignature(newSecret, header, BODY)).isTrue();
        assertThat(WebhookSignatureUtils.verifySignature(original, header, BODY)).isTrue();
    }

    @Test
    @DisplayName("re-encrypts the retired secret so one key version describes both columns")
    void retiredSecretIsReEncryptedNotCopied() {
        Endpoint endpoint = endpointWithSecret("original");
        String ciphertextBefore = endpoint.getSecretEncrypted();
        String ivBefore = endpoint.getSecretIv();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        service.rotateSecret(endpointId);

        /* A straight ciphertext copy would look right and decrypt today. It breaks after the
           next encryption-key rotation, when encryption_key_version moves on and the copied
           blob — sealed under the older key — no longer matches the version the row claims. */
        assertThat(endpoint.getSecretPreviousEncrypted())
                .as("AES-GCM with a fresh IV must not reproduce the original ciphertext")
                .isNotEqualTo(ciphertextBefore);
        assertThat(endpoint.getSecretPreviousIv()).isNotEqualTo(ivBefore);
        assertThat(endpoint.getEncryptionKeyVersion()).isEqualTo(registry.getActiveVersion());
    }

    @Test
    @DisplayName("a second rotation retires the secret from the first, not the one before it")
    void secondRotationShiftsTheWindow() {
        Endpoint endpoint = endpointWithSecret("first");
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        String second = service.rotateSecret(endpointId).getSecret();
        service.rotateSecret(endpointId);

        String kept = registry.decryptWithFallback(
                endpoint.getSecretPreviousEncrypted(),
                endpoint.getSecretPreviousIv(),
                endpoint.getEncryptionKeyVersion());
        assertThat(kept)
                .as("only one secret back is honoured; 'first' is gone")
                .isEqualTo(second);
    }

    @Test
    @DisplayName("rotates even when the current secret cannot be decrypted, opening no window")
    void undecryptableSecretStillRotates() {
        Endpoint endpoint = endpointWithSecret("original");
        endpoint.setSecretEncrypted("not-base64-ciphertext");
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        String newSecret = service.rotateSecret(endpointId).getSecret();

        /* Rotating is how an operator recovers from an unreadable secret, so it must not be
           the one thing they cannot do. And no window is opened: a secret nobody can read is
           one the receiver was not verifying with either. */
        assertThat(newSecret).isNotBlank();
        assertThat(endpoint.getSecretPreviousEncrypted()).isNull();
        assertThat(endpoint.getSecretRotatedAt()).isNull();
    }

    private Endpoint endpointWithSecret(String secret) {
        CryptoUtils.EncryptedData encrypted = registry.encrypt(secret);
        return Endpoint.builder()
                .id(endpointId)
                .projectId(projectId)
                .url("https://api.customer.com/webhooks")
                .secretEncrypted(encrypted.getCiphertext())
                .secretIv(encrypted.getIv())
                .encryptionKeyVersion(encrypted.getKeyVersion())
                .enabled(true)
                .build();
    }

    private static EncryptionKeyRegistry buildRegistry() throws Exception {
        EncryptionKeyRegistry reg = new EncryptionKeyRegistry();
        setField(reg, "singleKey", MASTER_KEY);
        setField(reg, "multiKeys", "");
        setField(reg, "configuredActiveVersion", 1);
        setField(reg, "salt", SALT);
        var init = reg.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(reg);
        return reg;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
