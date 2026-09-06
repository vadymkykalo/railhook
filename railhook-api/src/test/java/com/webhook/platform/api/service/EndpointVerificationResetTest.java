package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
@ExtendWith(MockitoExtension.class)
@DisplayName("EndpointService.updateEndpoint — verification follows the URL")
class EndpointVerificationResetTest {

    private static final String MASTER_KEY = "master_key_32_chars_long_padding";
    private static final String SALT = "test_salt_value";
    private static final String ORIGINAL_URL = "https://api.customer.com/webhooks";
    private static final String MOVED_URL = "https://collector.example.net/collect";
    private static final String ELSEWHERE_URL = "https://elsewhere.example.net/hook";

    /* UrlValidator short-circuits on an allow-listed host before it resolves anything, which
       is what keeps this a unit test: the assertions are about verification state, not about
       whether the machine running them has DNS. */
    private static final List<String> ALLOWED_HOSTS =
            List.of("api.customer.com", "collector.example.net", "elsewhere.example.net");

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
                true, ALLOWED_HOSTS, true);

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).name("p").build()));
        when(endpointRepository.saveAndFlush(any(Endpoint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("re-pointing a verified endpoint at a new URL sends it back to PENDING")
    void changingUrlResetsVerification() {
        Endpoint endpoint = verifiedEndpoint();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        service.updateEndpoint(endpointId, EndpointRequest.builder()
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
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        service.updateEndpoint(endpointId, EndpointRequest.builder()
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
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        service.updateEndpoint(endpointId, EndpointRequest.builder()
                .url(ELSEWHERE_URL)
                .build());

        /* SKIPPED passes the worker's gate exactly like VERIFIED, so leaving it in place
           would reopen the same hole for every endpoint created while the flag was off. */
        assertThat(endpoint.getVerificationStatus()).isEqualTo(Endpoint.VerificationStatus.PENDING);
        assertThat(endpoint.getVerificationSkipReason()).isNull();
    }

    @Test
    @DisplayName("with verification switched off, a moved URL lands on SKIPPED — not a silent outage")
    void changingUrlWithVerificationDisabledKeepsDelivering() throws Exception {
        EndpointService noVerification = new EndpointService(
                endpointRepository, projectRepository, WebClient.builder(), buildRegistry(),
                true, ALLOWED_HOSTS, false);

        Endpoint endpoint = verifiedEndpoint();
        endpoint.setVerificationStatus(Endpoint.VerificationStatus.SKIPPED);
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        noVerification.updateEndpoint(endpointId, EndpointRequest.builder()
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
        CryptoUtils.EncryptedData encrypted = registry.encrypt("the_secret");
        return Endpoint.builder()
                .id(endpointId)
                .projectId(projectId)
                .url(ORIGINAL_URL)
                .secretEncrypted(encrypted.getCiphertext())
                .secretIv(encrypted.getIv())
                .encryptionKeyVersion(encrypted.getKeyVersion())
                .enabled(true)
                .verificationStatus(Endpoint.VerificationStatus.VERIFIED)
                .verificationToken("tok_the_challenge_that_was_answered")
                .verificationAttemptedAt(Instant.now().minusSeconds(600))
                .verificationCompletedAt(Instant.now().minusSeconds(590))
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
