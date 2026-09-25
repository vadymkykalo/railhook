package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.ConsumerRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.common.enums.SignatureScheme;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import com.webhook.platform.common.util.WebhookSignatureUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointServiceTest {

    private static final String MASTER_KEY = "master_key_32_chars_long_padding";
    private static final String SALT = "test_salt_value";

    @Mock private EndpointRepository endpointRepository;
    @Mock private ProjectRepository projectRepository;

    private EncryptionKeyRegistry registry;

    private final UUID projectId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUpRegistry() throws Exception {
        registry = buildRegistry();
        when(endpointRepository.saveAndFlush(any(Endpoint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private EndpointService service(List<String> allowedHosts, boolean verificationRequired) {
        return new EndpointService(
                endpointRepository, projectRepository, mock(ConsumerRepository.class), WebClient.builder(), registry,
                true, allowedHosts, verificationRequired);
    }

    private Endpoint.EndpointBuilder endpointWithSecret(String url, String secret) {
        CryptoUtils.EncryptedData encrypted = registry.encrypt(secret);
        return Endpoint.builder()
                .id(endpointId)
                .projectId(projectId)
                .url(url)
                .secretEncrypted(encrypted.getCiphertext())
                .secretIv(encrypted.getIv())
                .encryptionKeyVersion(encrypted.getKeyVersion())
                .enabled(true);
    }

    private static EncryptionKeyRegistry buildRegistry() throws Exception {
        EncryptionKeyRegistry reg = new EncryptionKeyRegistry();
        setField(reg, "singleKey", MASTER_KEY);
        setField(reg, "multiKeys", "");
        setField(reg, "configuredActiveVersion", 1);
        setField(reg, "salt", SALT);
        Method init = reg.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(reg);
        return reg;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    // Rotation once replaced the secret in place, so every delivery failed until the receiver redeployed.
    @Nested
    @DisplayName("EndpointService.rotateSecret — the rotation grace window")
    class SecretRotation {

        private static final String BODY = "{\"id\":\"evt_1\"}";
        private static final String URL = "https://api.customer.com/webhooks";

        private EndpointService service;

        @BeforeEach
        void setUp() {
            service = service(Collections.emptyList(), false);
        }

        @Test
        @DisplayName("keeps the retired secret, and a receiver still holding it keeps verifying")
        void retiredSecretStaysValid() {
            String original = "the_secret_the_customer_deployed";
            Endpoint endpoint = endpointWithSecret(URL, original).build();
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            Instant before = Instant.now();
            String newSecret = service.rotateSecret(projectId, endpointId).getSecret();

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

            long ts = System.currentTimeMillis();
            String header = WebhookSignatureUtils.buildSignatureHeader(newSecret, kept, ts, BODY);
            assertThat(WebhookSignatureUtils.verifySignature(newSecret, header, BODY)).isTrue();
            assertThat(WebhookSignatureUtils.verifySignature(original, header, BODY)).isTrue();
        }

        @Test
        @DisplayName("re-encrypts the retired secret so one key version describes both columns")
        void retiredSecretIsReEncryptedNotCopied() {
            Endpoint endpoint = endpointWithSecret(URL, "original").build();
            String ciphertextBefore = endpoint.getSecretEncrypted();
            String ivBefore = endpoint.getSecretIv();
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            service.rotateSecret(projectId, endpointId);

            assertThat(endpoint.getSecretPreviousEncrypted())
                    .as("AES-GCM with a fresh IV must not reproduce the original ciphertext")
                    .isNotEqualTo(ciphertextBefore);
            assertThat(endpoint.getSecretPreviousIv()).isNotEqualTo(ivBefore);
            assertThat(endpoint.getEncryptionKeyVersion()).isEqualTo(registry.getActiveVersion());
        }

        @Test
        @DisplayName("a second rotation retires the secret from the first, not the one before it")
        void secondRotationShiftsTheWindow() {
            Endpoint endpoint = endpointWithSecret(URL, "first").build();
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            String second = service.rotateSecret(projectId, endpointId).getSecret();
            service.rotateSecret(projectId, endpointId);

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
            Endpoint endpoint = endpointWithSecret(URL, "original").build();
            endpoint.setSecretEncrypted("not-base64-ciphertext");
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            String newSecret = service.rotateSecret(projectId, endpointId).getSecret();

            assertThat(newSecret).isNotBlank();
            assertThat(endpoint.getSecretPreviousEncrypted()).isNull();
            assertThat(endpoint.getSecretRotatedAt()).isNull();
        }
    }

    // A partial PUT once wiped the description and the rate limit it did not mention.
    @Nested
    @DisplayName("EndpointService.updateEndpoint — absent means unchanged, empty means clear")
    class UpdateSemantics {

        private EndpointService service;

        @BeforeEach
        void setUp() {
            service = service(Collections.emptyList(), false);
        }

        private Endpoint existing() {
            Endpoint endpoint = Endpoint.builder()
                    .id(endpointId)
                    .projectId(projectId)
                    .url("https://example.com/hook")
                    .description("the one the team relies on")
                    .rateLimitPerSecond(25)
                    .allowedSourceIps("203.0.113.4")
                    .signatureScheme(SignatureScheme.LEGACY)
                    .build();
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));
            return endpoint;
        }

        private EndpointRequest urlOnly() {
            EndpointRequest request = new EndpointRequest();
            request.setUrl("https://example.com/hook");
            return request;
        }

        @Test
        void anUpdateThatMentionsNeitherLeavesTheDescriptionAndTheRateLimitAlone() {
            Endpoint endpoint = existing();

            service.updateEndpoint(projectId, endpointId, urlOnly());

            assertThat(endpoint.getDescription()).isEqualTo("the one the team relies on");
            assertThat(endpoint.getRateLimitPerSecond()).isEqualTo(25);
            assertThat(endpoint.getAllowedSourceIps()).isEqualTo("203.0.113.4");
            assertThat(endpoint.getSignatureScheme()).isEqualTo(SignatureScheme.LEGACY);
        }

        @Test
        void aBlankDescriptionClearsIt() {
            Endpoint endpoint = existing();
            EndpointRequest request = urlOnly();
            request.setDescription("");

            service.updateEndpoint(projectId, endpointId, request);

            assertThat(endpoint.getDescription()).isNull();
        }

        @Test
        void aZeroRateLimitRemovesTheThrottle() {
            Endpoint endpoint = existing();
            EndpointRequest request = urlOnly();
            request.setRateLimitPerSecond(0);

            service.updateEndpoint(projectId, endpointId, request);

            assertThat(endpoint.getRateLimitPerSecond()).isNull();
        }

        @Test
        void aBlankAllowedSourceIpsClearsTheAllowList() {
            Endpoint endpoint = existing();
            EndpointRequest request = urlOnly();
            request.setAllowedSourceIps("  ");

            service.updateEndpoint(projectId, endpointId, request);

            assertThat(endpoint.getAllowedSourceIps()).isNull();
        }

        @Test
        void creatingWithAZeroRateLimitMeansNoLimitRatherThanALimitOfZero() {
            EndpointRequest request = urlOnly();
            request.setRateLimitPerSecond(0);
            request.setDescription("  ");

            when(projectRepository.findById(projectId))
                    .thenReturn(Optional.of(Project.builder().id(projectId).name("p").build()));

            var response = service.createEndpoint(projectId, request);

            assertThat(response.getRateLimitPerSecond()).isNull();
            assertThat(response.getDescription()).isNull();
        }
    }

    // A verified endpoint could be re-pointed anywhere and keep receiving deliveries.
    @Nested
    @DisplayName("EndpointService.updateEndpoint — verification follows the URL")
    class VerificationReset {

        private static final String ORIGINAL_URL = "https://api.customer.com/webhooks";
        private static final String MOVED_URL = "https://collector.example.net/collect";
        private static final String ELSEWHERE_URL = "https://elsewhere.example.net/hook";

        private static final List<String> ALLOWED_HOSTS =
                List.of("api.customer.com", "collector.example.net", "elsewhere.example.net");

        private EndpointService service;

        @BeforeEach
        void setUp() {
            service = service(ALLOWED_HOSTS, true);
        }

        @Test
        @DisplayName("re-pointing a verified endpoint at a new URL sends it back to PENDING")
        void changingUrlResetsVerification() {
            Endpoint endpoint = verifiedEndpoint();
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            service.updateEndpoint(projectId, endpointId, EndpointRequest.builder()
                    .url(MOVED_URL)
                    .build());

            assertThat(endpoint.getVerificationStatus())
                    .as("the new URL has proved nothing, so the endpoint has to prove it again")
                    .isEqualTo(Endpoint.VerificationStatus.PENDING);
            assertThat(endpoint.getVerificationCompletedAt())
                    .as("a completion timestamp belongs to the URL that earned it")
                    .isNull();
            assertThat(endpoint.getVerificationToken())
                    .as("the old challenge cannot be replayed against the new URL")
                    .isNull();
        }

        @Test
        @DisplayName("an edit that leaves the URL alone keeps the endpoint delivering")
        void unchangedUrlKeepsVerification() {
            Endpoint endpoint = verifiedEndpoint();
            Instant completedAt = endpoint.getVerificationCompletedAt();
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            service.updateEndpoint(projectId, endpointId, EndpointRequest.builder()
                    .url(ORIGINAL_URL)
                    .description("renamed, nothing else")
                    .build());

            assertThat(endpoint.getVerificationStatus()).isEqualTo(Endpoint.VerificationStatus.VERIFIED);
            assertThat(endpoint.getVerificationCompletedAt()).isEqualTo(completedAt);
        }

        @Test
        @DisplayName("a SKIPPED endpoint is re-checked too when its URL moves")
        void changingUrlResetsSkippedVerification() {
            Endpoint endpoint = verifiedEndpoint();
            endpoint.setVerificationStatus(Endpoint.VerificationStatus.SKIPPED);
            endpoint.setVerificationSkipReason("verification was disabled when this was created");
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            service.updateEndpoint(projectId, endpointId, EndpointRequest.builder()
                    .url(ELSEWHERE_URL)
                    .build());

            assertThat(endpoint.getVerificationStatus()).isEqualTo(Endpoint.VerificationStatus.PENDING);
            assertThat(endpoint.getVerificationSkipReason()).isNull();
        }

        @Test
        @DisplayName("with verification switched off, a moved URL lands on SKIPPED — not a silent outage")
        void changingUrlWithVerificationDisabledKeepsDelivering() {
            EndpointService noVerification = service(ALLOWED_HOSTS, false);

            Endpoint endpoint = verifiedEndpoint();
            endpoint.setVerificationStatus(Endpoint.VerificationStatus.SKIPPED);
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            noVerification.updateEndpoint(projectId, endpointId, EndpointRequest.builder()
                    .url(MOVED_URL)
                    .build());

            assertThat(endpoint.getVerificationStatus()).isEqualTo(Endpoint.VerificationStatus.SKIPPED);
        }

        private Endpoint verifiedEndpoint() {
            return endpointWithSecret(ORIGINAL_URL, "the_secret")
                    .verificationStatus(Endpoint.VerificationStatus.VERIFIED)
                    .verificationToken("tok_the_challenge_that_was_answered")
                    .verificationAttemptedAt(Instant.now().minusSeconds(600))
                    .verificationCompletedAt(Instant.now().minusSeconds(590))
                    .build();
        }
    }
}
