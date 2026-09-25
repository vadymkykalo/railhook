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

    /** An enabled endpoint at {@code url} whose signing secret is sealed under the test registry. */
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
            Endpoint endpoint = endpointWithSecret(URL, "original").build();
            String ciphertextBefore = endpoint.getSecretEncrypted();
            String ivBefore = endpoint.getSecretIv();
            when(endpointRepository.findByIdAndProjectId(endpointId, projectId)).thenReturn(Optional.of(endpoint));

            service.rotateSecret(projectId, endpointId);

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

            /* Rotating is how an operator recovers from an unreadable secret, so it must not be
               the one thing they cannot do. And no window is opened: a secret nobody can read is
               one the receiver was not verifying with either. */
            assertThat(newSecret).isNotBlank();
            assertThat(endpoint.getSecretPreviousEncrypted()).isNull();
            assertThat(endpoint.getSecretRotatedAt()).isNull();
        }
    }

    /**
     * One rule for what an omitted field means on update, applied to every field.
     *
     * <p>{@code updateEndpoint} used to hold two rules at once, three lines apart: {@code secret},
     * {@code enabled}, {@code allowedSourceIps} and {@code signatureScheme} were left alone when the
     * request omitted them, under a comment explaining that null must mean "not specified" — while
     * {@code description} and {@code rateLimitPerSecond} were assigned straight from the request, so
     * an update that did not mention them wiped them. A partial PUT silently removed an endpoint's
     * throttle.
     *
     * <p>The rule now: an absent field is unchanged, and an explicitly empty value clears — blank for
     * a string, {@code 0} for the rate limit.
     */
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
        void aValueStillReplacesTheOldOne() {
            Endpoint endpoint = existing();
            EndpointRequest request = urlOnly();
            request.setDescription("renamed");
            request.setRateLimitPerSecond(90);

            service.updateEndpoint(projectId, endpointId, request);

            assertThat(endpoint.getDescription()).isEqualTo("renamed");
            assertThat(endpoint.getRateLimitPerSecond()).isEqualTo(90);
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

    /**
     * Verification has to survive the one edit that invalidates it.
     *
     * <p>{@code updateEndpoint} sets a new URL and, before this, left {@code verificationStatus}
     * alone. The worker's gate ({@code OutgoingAttemptStore}) only asks whether the status is
     * VERIFIED or SKIPPED, so an endpoint verified against a URL its owner controlled could be
     * re-pointed anywhere and keep receiving deliveries — the whole feature was a one-time check
     * with a hole the size of a PUT.
     *
     * <p>The symmetric half matters as much: an edit that does not touch the URL must not reset
     * anything, or renaming an endpoint's description would silently stop its deliveries.
     */
    @Nested
    @DisplayName("EndpointService.updateEndpoint — verification follows the URL")
    class VerificationReset {

        private static final String ORIGINAL_URL = "https://api.customer.com/webhooks";
        private static final String MOVED_URL = "https://collector.example.net/collect";
        private static final String ELSEWHERE_URL = "https://elsewhere.example.net/hook";

        /* UrlValidator short-circuits on an allow-listed host before it resolves anything, which
           is what keeps this a unit test: the assertions are about verification state, not about
           whether the machine running them has DNS. */
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

            /* Resetting on every update would make editing a description an outage: the worker
               terminally fails a delivery to an unverified endpoint. */
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

            /* SKIPPED passes the worker's gate exactly like VERIFIED, so leaving it in place
               would reopen the same hole for every endpoint created while the flag was off. */
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

            /* The worker's gate is NOT behind webhook.endpoint-verification-required: it always
               demands VERIFIED or SKIPPED. Forcing PENDING here would therefore stop delivery
               permanently for the default configuration, where nobody is ever asked to verify and
               so nothing would ever move the status back. Re-deriving what createEndpoint would
               have produced at this URL is the rule that holds under both settings. */
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
