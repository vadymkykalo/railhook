package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.service.billing.QuotaCounterService;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.service.ingress.RateLimitExceededException;
import com.webhook.platform.api.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.security.SuspensionCheck;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.ingress.HeaderSanitizer;
import com.webhook.platform.api.service.ingress.IngressOutcome;
import com.webhook.platform.api.service.ingress.OrganizationSuspendedException;
import com.webhook.platform.api.service.ingress.PayloadTooLargeException;
import com.webhook.platform.api.service.ingress.SignatureVerificationFailedException;
import com.webhook.platform.api.service.ingress.SourceDisabledException;
import com.webhook.platform.api.service.ingress.SourceNotFoundException;
import com.webhook.platform.api.service.verification.ReplayDetectionService;
import com.webhook.platform.api.service.verification.WebhookVerifierFactory;
import com.webhook.platform.api.service.ForwardDispatch;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngressServiceTest {

    private final UUID orgId = UUID.randomUUID();

    @Mock
    private IncomingSourceRepository sourceRepository;
    @Mock
    private IncomingEventRepository eventRepository;
    @Mock
    private IncomingDestinationRepository destinationRepository;
    @Mock
    private IncomingForwardAttemptRepository forwardAttemptRepository;
    @Mock
    private OutboxMessageRepository outboxMessageRepository;
    @Mock
    private HttpServletRequest httpRequest;
    @Mock
    private RedisRateLimiterService rateLimiterService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;
    @Mock
    private ReplayDetectionService replayDetectionService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private QuotaCounterService quotaCounterService;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SuspensionCheck suspensionCheck;

    private IngressService service;
    private WebhookVerifierFactory verifierFactory;
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String ENCRYPTION_KEY = "test_encryption_key_32_chars_pad";
    private static final String ENCRYPTION_SALT = "test_salt";
    private EncryptionKeyRegistry encryptionKeyRegistry;

    private static final int DEFAULT_RATE_LIMIT = 100;

    private final UUID sourceId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID destId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(rateLimiterService.tryAcquireForSourceFailClosed(any(UUID.class), anyInt())).thenReturn(true);
        when(projectRepository.existsById(any())).thenReturn(true);
        encryptionKeyRegistry = createTestRegistry(ENCRYPTION_KEY, ENCRYPTION_SALT);
        verifierFactory = new WebhookVerifierFactory("http://localhost:8080");
        TrustedProxyResolver clientIpResolver = new TrustedProxyResolver(
                List.of("127.0.0.1", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"));
        service = new IngressService(
                sourceRepository, eventRepository, destinationRepository,
                forwardAttemptRepository, outboxMessageRepository,
                objectMapper, new ForwardDispatch(objectMapper), meterRegistry, verifierFactory, replayDetectionService, rateLimiterService,
                clientIpResolver, transactionManager,
                encryptionKeyRegistry, entitlementService, quotaCounterService, projectRepository, suspensionCheck, 524288, DEFAULT_RATE_LIMIT
        );
    }

    private static EncryptionKeyRegistry createTestRegistry(String key, String salt) throws Exception {
        EncryptionKeyRegistry registry = new EncryptionKeyRegistry();
        setField(registry, "singleKey", key);
        setField(registry, "multiKeys", "");
        setField(registry, "configuredActiveVersion", 0);
        setField(registry, "salt", salt);
        var init = registry.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(registry);
        return registry;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private IncomingSource buildActiveSource() {
        return IncomingSource.builder()
                .id(sourceId).projectId(UUID.randomUUID()).organizationId(orgId)
                .name("Test").slug("test").providerType(ProviderType.GENERIC)
                .status(IncomingSourceStatus.ACTIVE)
                .ingressPathToken("validtoken")
                .verificationMode(VerificationMode.NONE)
                .hmacHeaderName("X-Signature").hmacSignaturePrefix("")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    private static IncomingEvent accepted(IngressOutcome outcome) {
        assertThat(outcome).isInstanceOf(IngressOutcome.Accepted.class);
        return ((IngressOutcome.Accepted) outcome).event();
    }

    private void stubHttpRequest() {
        when(httpRequest.getMethod()).thenReturn("POST");
        when(httpRequest.getRequestURI()).thenReturn("/ingress/validtoken");
        when(httpRequest.getContentType()).thenReturn("application/json");
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(httpRequest.getHeaderNames()).thenReturn(Collections.enumeration(List.of("content-type")));
        when(httpRequest.getHeader(anyString())).thenReturn(null);
        when(httpRequest.getHeader("content-type")).thenReturn("application/json");
    }

    @Test
    void receiveWebhook_success_noDestinations() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();

        assertThat(meterRegistry.get("events_ingested_total").tag("direction", "incoming").counter().count())
                .as("registered before the first webhook, so a quiet deployment reads 0 rather than no data")
                .isZero();

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", "{\"test\":true}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(meterRegistry.get("events_ingested_total").tag("direction", "incoming").counter().count())
                .isEqualTo(1.0);
        assertThat(event.getId()).isEqualTo(eventId);
        assertThat(event.getIncomingSourceId()).isEqualTo(sourceId);
        assertThat(event.getBodyRaw()).isEqualTo("{\"test\":true}");
        assertThat(event.getMethod()).isEqualTo("POST");
        assertThat(event.getRequestId()).isNotNull();
        assertThat(event.getBodySha256()).isNotNull();
        assertThat(event.getVerified()).isNull(); // verification mode NONE

        verify(forwardAttemptRepository, never()).saveAll(any());
        verify(outboxMessageRepository, never()).saveAll(any());
    }

    // A non-UTF-8 body loses bytes as text, so the bytes themselves are kept.
    @Test
    void aBodyThatIsNotUtf8IsKeptByteForByte() {
        stubAcceptingSourceWithoutDestinations();
        byte[] arrived = {(byte) 0x7B, (byte) 0xC0, (byte) 0xFF, (byte) 0x7D};

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", arrived, httpRequest));

        assertThat(event.getBodyBytes()).isEqualTo(arrived);
        assertThat(event.getBodyRaw()).as("still shown, with replacement characters").isNotNull();
    }

    // A NUL byte once failed the insert and a verified webhook got a 500.
    @Test
    void aBodyWithANulByteIsKeptAsBytesAndShownWithoutIt() {
        stubAcceptingSourceWithoutDestinations();
        byte[] arrived = "a\u0000b".getBytes(StandardCharsets.UTF_8);

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", arrived, httpRequest));

        assertThat(event.getBodyBytes()).isEqualTo(arrived);
        assertThat(event.getBodyRaw()).doesNotContain("\u0000");
    }

    @Test
    void aUtf8BodyIsStoredOnceAsText() {
        stubAcceptingSourceWithoutDestinations();

        IncomingEvent event = accepted(service.receiveWebhook("validtoken",
                "{\"name\":\"Zoë\"}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(event.getBodyRaw()).isEqualTo("{\"name\":\"Zoë\"}");
        assertThat(event.getBodyBytes()).as("the text already encodes back to the same bytes").isNull();
    }

    private void stubAcceptingSourceWithoutDestinations() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();
    }

    @Test
    void receiveWebhook_success_withDestinations() {
        IncomingSource source = buildActiveSource();
        IncomingDestination dest = IncomingDestination.builder()
                .id(destId).incomingSourceId(sourceId)
                .url("https://example.com/hook")
                .authType(IncomingAuthType.NONE)
                .enabled(true).maxAttempts(5).timeoutSeconds(30)
                .retryDelays("60,300")
                .build();

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of(dest));
        stubHttpRequest();

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", "{\"data\":1}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(event.getId()).isEqualTo(eventId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncomingForwardAttempt>> attemptCaptor = ArgumentCaptor.forClass(List.class);
        verify(forwardAttemptRepository).saveAll(attemptCaptor.capture());
        List<IncomingForwardAttempt> attempts = attemptCaptor.getValue();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getDestinationId()).isEqualTo(destId);
        assertThat(attempts.get(0).getStatus()).isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutboxMessage>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxMessageRepository).saveAll(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue()).hasSize(1);
    }

    @Test
    void receiveWebhook_payloadTooLarge_throws() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));

        String hugeBody = "x".repeat(600000);

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", hugeBody.getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(PayloadTooLargeException.class)
                .hasMessageContaining("524288");
    }

    @Test
    void receiveWebhook_hmacVerification_success() {
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        String body = "{\"test\":true}";
        String expectedHmac = computeHmac(secret, body);

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(expectedHmac);

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(event.getVerified()).isTrue();
        assertThat(event.getVerificationError()).isNull();
    }

    @Test
    void receiveWebhook_hmacVerification_mismatch_withDestinations_blocksForwarding() {
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        IncomingDestination dest = IncomingDestination.builder()
                .id(destId).incomingSourceId(sourceId)
                .url("https://example.com/hook")
                .authType(IncomingAuthType.NONE)
                .enabled(true).maxAttempts(5).timeoutSeconds(30)
                .retryDelays("60,300")
                .build();

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn("bad-sig");

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{\"data\":1}".getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class);

        // Rejected before dedup/save, or a forged request could poison dedup.
        verify(eventRepository, never()).save(any(IncomingEvent.class));
        verify(forwardAttemptRepository, never()).saveAll(any());
        verify(outboxMessageRepository, never()).saveAll(any());
    }

    @Test
    void receiveWebhook_dedupPoisoning_attackerCannotBlockLegitimateWebhook() {
        // A forged request carrying a known providerEventId once blocked the genuine one.
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();

        String body = "{\"data\":\"important\"}";

        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_target");
        when(httpRequest.getHeader("X-Signature")).thenReturn("attacker-bad-sig");

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class);

        verify(eventRepository, never()).save(any(IncomingEvent.class));

        String validHmac = computeHmac(secret, body);
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_target"))
                .thenReturn(Optional.empty());
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getId()).isEqualTo(eventId);
        assertThat(result.getVerified()).isTrue();
        assertThat(result.getProviderEventId()).isEqualTo("evt_target");
        verify(eventRepository).save(any(IncomingEvent.class));
    }

    @Test
    void receiveWebhook_replayDetection_rejectsReplayedSignature() {
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        String body = "{\"test\":true}";
        String validHmac = computeHmac(secret, body);

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);

        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(true);

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class)
                .hasMessageContaining("Replay attack detected");

        verify(eventRepository, never()).save(any(IncomingEvent.class));
    }

    // Identical bodies from two genuine deliveries once collided as a replay; the delivery id is part of the key.
    @Test
    void receiveWebhook_replayDetection_aDifferentDeliveryWithTheSameBodyIsNotAReplay() {
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        String body = "{\"test\":true}";
        String validHmac = computeHmac(secret, body);

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("delivery-2");

        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(true);

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(event.getVerified()).isTrue();
        verify(replayDetectionService).isReplay(eq(sourceId.toString()), eq(validHmac + ":delivery-2"));
    }

    @Test
    void receiveWebhook_nullBody_accepted() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", (byte[]) null, httpRequest));

        assertThat(event.getBodyRaw()).isNull();
        assertThat(event.getBodySha256()).isNull();
    }

    @Test
    void receiveWebhook_xForwardedFor_extractsClientIp() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();
        // Only the right-most hop added by the trusted proxy is the real client.
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18");

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(event.getClientIp()).isEqualTo("70.41.3.18");
    }

    @Test
    void receiveWebhook_duplicateProviderEventId_returnsExisting() {
        IncomingSource source = buildActiveSource();
        IncomingEvent existing = IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("old-req").method("POST")
                .providerEventId("evt_123")
                .receivedAt(Instant.now())
                .build();

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_123");
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_123"))
                .thenReturn(Optional.of(existing));

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", "{\"data\":1}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getId()).isEqualTo(eventId);
        verify(eventRepository, never()).save(any(IncomingEvent.class));
        verify(forwardAttemptRepository, never()).saveAll(any());
    }

    // Without GitLab's Idempotency-Key a resend after the replay window was forwarded twice.
    @Test
    void aGitLabResendCarryingTheSameIdempotencyKeyReturnsTheStoredEvent() {
        IncomingSource source = buildActiveSource();
        IncomingEvent existing = IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("old-req").method("POST")
                .providerEventId("f5e5f430-f57b-4e6e-9fac-d9128cd7232f")
                .receivedAt(Instant.now())
                .build();

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Gitlab-Event")).thenReturn("Push Hook");
        when(httpRequest.getHeader("X-Gitlab-Event-UUID")).thenReturn("13792a34-cac6-4fda-95a8-c58e00a3954e");
        when(httpRequest.getHeader("Idempotency-Key")).thenReturn("f5e5f430-f57b-4e6e-9fac-d9128cd7232f");
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "f5e5f430-f57b-4e6e-9fac-d9128cd7232f"))
                .thenReturn(Optional.of(existing));

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", "{\"object_kind\":\"push\"}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getId()).isEqualTo(eventId);
        verify(eventRepository, never()).save(any(IncomingEvent.class));
        verify(forwardAttemptRepository, never()).saveAll(any());
    }

    @Test
    void aGitLabDeliveryIsKeyedByWebhookIdWhenGitLabSendsIt() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(eq(sourceId), anyString()))
                .thenReturn(Optional.empty());
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();
        when(httpRequest.getHeader("X-Gitlab-Event")).thenReturn("Push Hook");
        when(httpRequest.getHeader("webhook-id")).thenReturn("msg_2b3c");

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getProviderEventId()).isEqualTo("msg_2b3c");
    }

    // Recursive webhooks share X-Gitlab-Event-UUID, so it is not a delivery id.
    @Test
    void aGitLabEventUuidAloneIsNotTakenForADeliveryId() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();
        when(httpRequest.getHeader("X-Gitlab-Event")).thenReturn("Pipeline Hook");
        when(httpRequest.getHeader("X-Gitlab-Event-UUID")).thenReturn("13792a34-cac6-4fda-95a8-c58e00a3954e");

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getProviderEventId()).isNull();
        verify(eventRepository, never()).findByIncomingSourceIdAndProviderEventId(any(), any());
    }

    @Test
    void receiveWebhook_duplicateRace_resolvesGracefully() {
        IncomingSource source = buildActiveSource();
        IncomingEvent existing = IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("first-req").method("POST")
                .providerEventId("evt_race")
                .receivedAt(Instant.now())
                .build();

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_race");
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(eventRepository.save(any(IncomingEvent.class)))
                .thenThrow(new DataIntegrityViolationException("Unique index violation"));

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", "{\"data\":1}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getId()).isEqualTo(eventId);
        assertThat(result.getProviderEventId()).isEqualTo("evt_race");
        verify(forwardAttemptRepository, never()).saveAll(any());
    }

    @Test
    void receiveWebhook_duplicateRace_noProviderEventId_rethrows() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(eventRepository.save(any(IncomingEvent.class)))
                .thenThrow(new DataIntegrityViolationException("Unique index violation"));

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{\"data\":1}".getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void receiveWebhook_noProviderEventId_noDedupOnBodyHash() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();

        IncomingEvent first = accepted(service.receiveWebhook("validtoken", "{\"status\":\"active\"}".getBytes(StandardCharsets.UTF_8), httpRequest));
        IncomingEvent second = accepted(service.receiveWebhook("validtoken", "{\"status\":\"active\"}".getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getProviderEventId()).isNull();
        assertThat(second.getProviderEventId()).isNull();
        assertThat(first.getBodySha256()).isEqualTo(second.getBodySha256());
        verify(eventRepository, times(2)).save(any(IncomingEvent.class));
    }

    // Slack connects a Request URL only after it echoes a signed url_verification challenge.

    private static final String SLACK_SECRET = "slack-signing-secret";
    private static final String URL_VERIFICATION =
            "{\"token\":\"Jhj5dZrVaK7ZwHHjRyZWjbDl\",\"challenge\":\"3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P\","
                    + "\"type\":\"url_verification\"}";

    private IncomingSource slackSource() {
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(SLACK_SECRET, ENCRYPTION_KEY, ENCRYPTION_SALT);
        IncomingSource source = buildActiveSource();
        source.setProviderType(ProviderType.SLACK);
        source.setVerificationMode(VerificationMode.PROVIDER);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        return source;
    }

    private void signLikeSlack(String secret, String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        when(httpRequest.getHeader("X-Slack-Request-Timestamp")).thenReturn(timestamp);
        when(httpRequest.getHeader("X-Slack-Signature"))
                .thenReturn("v0=" + computeHmac(secret, "v0:" + timestamp + ":" + body));
    }

    @Test
    void aVerifiedSlackUrlVerificationIsAnsweredWithItsChallengeAndNothingIsStored() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(slackSource()));
        stubHttpRequest();
        signLikeSlack(SLACK_SECRET, URL_VERIFICATION);

        IngressOutcome outcome = service.receiveWebhook("validtoken",
                URL_VERIFICATION.getBytes(StandardCharsets.UTF_8), httpRequest);

        assertThat(outcome).isEqualTo(new IngressOutcome.SlackUrlVerification(
                "3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P"));
        verify(eventRepository, never()).save(any());
        verify(outboxMessageRepository, never()).saveAll(any());
        verify(entitlementService, never()).checkEventQuota();
        verify(quotaCounterService, never()).increment();
        verify(replayDetectionService, never()).isReplay(any(), any());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void anOverQuotaOrganizationCanStillConnectASlackApp() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(slackSource()));
        stubHttpRequest();
        signLikeSlack(SLACK_SECRET, URL_VERIFICATION);
        doThrow(new QuotaExceededException("events_per_month", 1000, 1000, "Free"))
                .when(entitlementService).checkEventQuota();

        IngressOutcome outcome = service.receiveWebhook("validtoken",
                URL_VERIFICATION.getBytes(StandardCharsets.UTF_8), httpRequest);

        assertThat(outcome).isInstanceOf(IngressOutcome.SlackUrlVerification.class);
    }

    @Test
    void aSlackUrlVerificationWithABadSignatureIsRefusedAndNotEchoed() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(slackSource()));
        stubHttpRequest();
        signLikeSlack("not-the-signing-secret", URL_VERIFICATION);

        assertThatThrownBy(() -> service.receiveWebhook("validtoken",
                URL_VERIFICATION.getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void aSlackUrlVerificationToASuspendedOrganizationIsRefused() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(slackSource()));
        when(suspensionCheck.suspensionReason(orgId)).thenReturn(Optional.of("abuse"));
        stubHttpRequest();
        signLikeSlack(SLACK_SECRET, URL_VERIFICATION);

        assertThatThrownBy(() -> service.receiveWebhook("validtoken",
                URL_VERIFICATION.getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(OrganizationSuspendedException.class);
    }

    @Test
    void aSlackSourceWithoutVerificationDoesNotEchoAChallengeItCannotAuthenticate() {
        IncomingSource source = slackSource();
        source.setVerificationMode(VerificationMode.NONE);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        stubHttpRequest();

        IngressOutcome outcome = service.receiveWebhook("validtoken",
                URL_VERIFICATION.getBytes(StandardCharsets.UTF_8), httpRequest);

        assertThat(outcome).isInstanceOf(IngressOutcome.Accepted.class);
    }

    @Test
    void aVerifiedSlackEventCallbackIsStillStoredAsAnEvent() {
        String eventCallback = "{\"type\":\"event_callback\",\"event_id\":\"Ev0PV52K25\",\"challenge\":\"x\"}";
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(slackSource()));
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        stubHttpRequest();
        signLikeSlack(SLACK_SECRET, eventCallback);

        IncomingEvent event = accepted(service.receiveWebhook("validtoken",
                eventCallback.getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(event.getProviderEventId()).isEqualTo("Ev0PV52K25");
        assertThat(event.getVerified()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "Authorization, true", "cookie, true", "Set-Cookie, true", "X-Api-Key, true",
            "Proxy-Authorization, true", "Stripe-Signature, true", "X-Hub-Signature-256, true",
            "X-Shopify-Hmac-SHA256, true", "X-Twilio-Signature, true", "X-Slack-Signature, true",
            "X-Webhook-Secret, true", "X-Auth-Token, true", "X-Access-Token, true", "X-Credential-Id, true",
            "X-Password-Hash, true", "Content-Type, false", "User-Agent, false", "Accept, false",
            "X-Request-Id, false", "X-Webhook-Id, false", "X-GitHub-Event, false", "X-GitHub-Delivery, false",
            "Host, false"
    })
    void isSensitiveHeader(String header, boolean sensitive) {
        assertThat(HeaderSanitizer.isSensitiveHeader(header)).isEqualTo(sensitive);
    }

    @Test
    void receiveWebhook_invalidToken_rejectedWithoutOpeningTransaction() {
        when(sourceRepository.findByIngressPathToken("invalid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receiveWebhook("invalid", "{}".getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(SourceNotFoundException.class);

        // No transaction, so a bad token never holds a pooled connection.
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void receiveWebhook_disabledSource_rejectedWithoutOpeningTransaction() {
        IncomingSource source = buildActiveSource();
        source.setStatus(IncomingSourceStatus.DISABLED);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(SourceDisabledException.class);

        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void receiveWebhook_signatureMismatch_rejectedWithoutOpeningTransaction() {
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn("wrong-signature");

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{\"test\":true}".getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class);

        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void receiveWebhook_failedPersistWithNoExistingRow_releasesReplayMarker() {
        // A marker left behind after a failed persist turned the provider's resend into a replay.
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        String body = "{\"test\":true}";
        String validHmac = computeHmac(secret, body);

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(false);
        when(eventRepository.save(any(IncomingEvent.class)))
                .thenThrow(new DataIntegrityViolationException("some other constraint violation"));

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(replayDetectionService).unmark(sourceId.toString(), validHmac);
    }

    @Test
    void receiveWebhook_duplicateRaceResolvedToExistingRow_doesNotReleaseReplayMarker() {
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        String body = "{\"data\":1}";
        String validHmac = computeHmac(secret, body);

        IncomingEvent existing = IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("first-req").method("POST")
                .providerEventId("evt_race")
                .receivedAt(Instant.now())
                .build();

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_race");
        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(false);
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(eventRepository.save(any(IncomingEvent.class)))
                .thenThrow(new DataIntegrityViolationException("Unique index violation"));

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getId()).isEqualTo(eventId);
        verify(replayDetectionService, never()).unmark(any(), any());
    }

    @Test
    void receiveWebhook_successfulPersist_doesNotReleaseReplayMarker() {
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        String body = "{\"test\":true}";
        String validHmac = computeHmac(secret, body);

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(false);

        IncomingEvent event = accepted(service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(event.getId()).isEqualTo(eventId);
        verify(replayDetectionService, never()).unmark(any(), any());
    }

    // Replay and quota ran before dedup, so a provider's resend of an accepted webhook got 401 or 429.

    private IncomingSource signedGenericSource(String secret) {
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);
        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");
        return source;
    }

    private IncomingEvent acceptedEvent(String providerEventId) {
        return IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("first-req").method("POST")
                .providerEventId(providerEventId)
                .receivedAt(Instant.now())
                .build();
    }

    @Test
    void aResendOfAnAcceptedSignedWebhookReturnsTheStoredEventInsteadOfAReplayRejection() {
        String secret = "my-hmac-secret";
        String body = "{\"data\":1}";
        String validHmac = computeHmac(secret, body);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(signedGenericSource(secret)));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_resent");
        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(true);
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_resent"))
                .thenReturn(Optional.of(acceptedEvent("evt_resent")));

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getId()).isEqualTo(eventId);
        verify(eventRepository, never()).save(any(IncomingEvent.class));
        verify(quotaCounterService, never()).increment();
    }

    @Test
    void aResendThatDedupsDoesNotConsumeTheReplayMarker() {
        String secret = "my-hmac-secret";
        String body = "{\"data\":1}";
        String validHmac = computeHmac(secret, body);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(signedGenericSource(secret)));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_resent");
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_resent"))
                .thenReturn(Optional.of(acceptedEvent("evt_resent")));

        service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest);

        verify(replayDetectionService, never()).isReplay(any(), any());
    }

    @Test
    void anOverQuotaOrganizationStillGetsTheStoredEventForAResend() {
        String secret = "my-hmac-secret";
        String body = "{\"data\":1}";
        String validHmac = computeHmac(secret, body);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(signedGenericSource(secret)));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_resent");
        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(true);
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_resent"))
                .thenReturn(Optional.of(acceptedEvent("evt_resent")));
        doThrow(new QuotaExceededException("events_per_month", 1000, 1000, "Free"))
                .when(entitlementService).checkEventQuota();

        IncomingEvent result = accepted(service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest));

        assertThat(result.getId()).isEqualTo(eventId);
        verify(eventRepository, never()).save(any(IncomingEvent.class));
    }

    @Test
    void aBadSignatureCarryingAKnownProviderEventIdIsRefusedAndNeverShownTheStoredEvent() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(signedGenericSource("my-hmac-secret")));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn("forged");
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_resent");
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_resent"))
                .thenReturn(Optional.of(acceptedEvent("evt_resent")));

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{\"data\":1}".getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class);
        verify(eventRepository, never()).findByIncomingSourceIdAndProviderEventId(any(), any());
    }

    @Test
    void anOverQuotaNewEventDoesNotBurnItsReplayMarker() {
        // Otherwise the retry, once there is room, would be refused as a replay.
        String secret = "my-hmac-secret";
        String body = "{\"data\":2}";
        String validHmac = computeHmac(secret, body);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(signedGenericSource(secret)));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn(validHmac);
        doThrow(new QuotaExceededException("events_per_month", 1000, 1000, "Free"))
                .when(entitlementService).checkEventQuota();

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", body.getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(QuotaExceededException.class);
        verify(replayDetectionService, never()).isReplay(any(), any());
    }

    private String computeHmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void overTheMonthlyQuota_isRejectedWithoutPersistingAnything() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(buildActiveSource()));
        stubHttpRequest();
        doThrow(new QuotaExceededException("events_per_month", 1000, 1000, "Free"))
                .when(entitlementService).checkEventQuota();

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(QuotaExceededException.class);

        verify(eventRepository, never()).save(any());
        verify(quotaCounterService, never()).increment();
    }

    @Test
    void anAcceptedWebhookChargesTheOrganizationOnce() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(buildActiveSource()));
        stubHttpRequest();
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest);

        verify(entitlementService).checkEventQuota();
        verify(quotaCounterService).increment();
    }

    @Test
    void aDeduplicatedWebhookIsNotChargedAgain() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(buildActiveSource()));
        stubHttpRequest();
        when(httpRequest.getHeader("X-GitHub-Delivery")).thenReturn("gh-1");
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "gh-1"))
                .thenReturn(Optional.of(IncomingEvent.builder().id(eventId).incomingSourceId(sourceId).build()));

        service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest);

        verify(eventRepository, never()).save(any());
        verify(quotaCounterService, never()).increment();
    }

    @Test
    void aSourceWithNoRateLimitOfItsOwnFallsBackToTheConfiguredDefault() {
        IncomingSource source = buildActiveSource();
        source.setRateLimitPerSecond(null);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rateLimiterService.tryAcquireForSourceFailClosed(sourceId, DEFAULT_RATE_LIMIT)).thenReturn(true);

        service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest);

        verify(rateLimiterService).tryAcquireForSourceFailClosed(sourceId, DEFAULT_RATE_LIMIT);
    }

    @Test
    void exceedingTheDefaultRateLimitRejectsTheWebhook() {
        IncomingSource source = buildActiveSource();
        source.setRateLimitPerSecond(null);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(rateLimiterService.tryAcquireForSourceFailClosed(sourceId, DEFAULT_RATE_LIMIT)).thenReturn(false);

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{}".getBytes(StandardCharsets.UTF_8), httpRequest))
                .isInstanceOf(RateLimitExceededException.class);
        verify(eventRepository, never()).save(any());
    }
}
