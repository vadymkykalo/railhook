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
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.ingress.HeaderSanitizer;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        // Every Source now has a rate limit — its own or the configured default — and the limiter
        // is fail-closed, so a test that says nothing about it would be rejected before it starts.
        when(rateLimiterService.tryAcquireForSourceFailClosed(any(UUID.class), anyInt())).thenReturn(true);
        encryptionKeyRegistry = createTestRegistry(ENCRYPTION_KEY, ENCRYPTION_SALT);
        verifierFactory = new WebhookVerifierFactory("http://localhost:8080");
        TrustedProxyResolver clientIpResolver = new TrustedProxyResolver(
                List.of("127.0.0.1", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"));
        service = new IngressService(
                sourceRepository, eventRepository, destinationRepository,
                forwardAttemptRepository, outboxMessageRepository,
                objectMapper, new ForwardDispatch(objectMapper), meterRegistry, verifierFactory, replayDetectionService, rateLimiterService,
                clientIpResolver, transactionManager,
                encryptionKeyRegistry, entitlementService, quotaCounterService, 524288, DEFAULT_RATE_LIMIT
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
                // The Source names its organization: ingress is unauthenticated, so the tenant
                // scope the rest of the request runs in comes off this row.
                .id(sourceId).projectId(UUID.randomUUID()).organizationId(orgId)
                .name("Test").slug("test").providerType(ProviderType.GENERIC)
                .status(IncomingSourceStatus.ACTIVE)
                .ingressPathToken("validtoken")
                .verificationMode(VerificationMode.NONE)
                .hmacHeaderName("X-Signature").hmacSignaturePrefix("")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
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

        IncomingEvent event = service.receiveWebhook("validtoken", "{\"test\":true}", httpRequest);

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

        IncomingEvent event = service.receiveWebhook("validtoken", "{\"data\":1}", httpRequest);

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
    void receiveWebhook_invalidToken_throws() {
        when(sourceRepository.findByIngressPathToken("invalid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receiveWebhook("invalid", "{}", httpRequest))
                .isInstanceOf(SourceNotFoundException.class);
    }

    @Test
    void receiveWebhook_disabledSource_throws() {
        IncomingSource source = buildActiveSource();
        source.setStatus(IncomingSourceStatus.DISABLED);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{}", httpRequest))
                .isInstanceOf(SourceDisabledException.class);
    }

    @Test
    void receiveWebhook_payloadTooLarge_throws() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));

        // Service configured with maxPayloadSizeBytes=524288, create larger body
        String hugeBody = "x".repeat(600000);

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", hugeBody, httpRequest))
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
        // Compute expected HMAC
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

        IncomingEvent event = service.receiveWebhook("validtoken", body, httpRequest);

        assertThat(event.getVerified()).isTrue();
        assertThat(event.getVerificationError()).isNull();
    }

    @Test
    void receiveWebhook_hmacVerification_mismatch_throwsAndBlocksForwarding() {
        String secret = "my-hmac-secret";
        CryptoUtils.EncryptedData encrypted = CryptoUtils.encryptSecret(secret, ENCRYPTION_KEY, ENCRYPTION_SALT);

        IncomingSource source = buildActiveSource();
        source.setVerificationMode(VerificationMode.HMAC_GENERIC);
        source.setHmacSecretEncrypted(encrypted.getCiphertext());
        source.setHmacSecretIv(encrypted.getIv());
        source.setHmacHeaderName("X-Signature");
        source.setHmacSignaturePrefix("");

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(eventId);
            return e;
        });
        stubHttpRequest();
        when(httpRequest.getHeader("X-Signature")).thenReturn("wrong-signature");

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{\"test\":true}", httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class)
                .hasMessageContaining("Signature mismatch");

        // Event is NOT persisted — rejected before dedup/save to prevent dedup poisoning
        verify(eventRepository, never()).save(any(IncomingEvent.class));
        verify(forwardAttemptRepository, never()).saveAll(any());
        verify(outboxMessageRepository, never()).saveAll(any());
        verify(destinationRepository, never()).findByIncomingSourceIdAndEnabledTrue(any());
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

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{\"data\":1}", httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class);

        // Event is NOT persisted — rejected before dedup/save to prevent dedup poisoning
        verify(eventRepository, never()).save(any(IncomingEvent.class));
        verify(forwardAttemptRepository, never()).saveAll(any());
        verify(outboxMessageRepository, never()).saveAll(any());
    }

    @Test
    void receiveWebhook_dedupPoisoning_attackerCannotBlockLegitimateWebhook() {
        // Regression test for P0 dedup poisoning vulnerability.
        // Attacker sends webhook with known providerEventId but invalid signature.
        // The legitimate webhook with the same providerEventId must still be accepted.
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

        // Step 1: Attacker sends webhook with known providerEventId but bad signature
        when(httpRequest.getHeader("X-Webhook-Id")).thenReturn("evt_target");
        when(httpRequest.getHeader("X-Signature")).thenReturn("attacker-bad-sig");

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", body, httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class);

        // Attacker's event must NOT be persisted
        verify(eventRepository, never()).save(any(IncomingEvent.class));

        // Step 2: Legitimate webhook with same providerEventId and valid signature
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

        IncomingEvent result = service.receiveWebhook("validtoken", body, httpRequest);

        // Legitimate webhook is accepted and persisted
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

        // ReplayDetectionService says this signature was already seen
        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(true);

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", body, httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class)
                .hasMessageContaining("Replay attack detected");

        // Event must NOT be persisted
        verify(eventRepository, never()).save(any(IncomingEvent.class));
    }

    @Test
    void receiveWebhook_replayDetection_allowsFirstRequest() {
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

        // First time — not a replay
        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(false);

        IncomingEvent event = service.receiveWebhook("validtoken", body, httpRequest);

        assertThat(event.getVerified()).isTrue();
        verify(eventRepository).save(any(IncomingEvent.class));
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

        IncomingEvent event = service.receiveWebhook("validtoken", null, httpRequest);

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
        // Peer (127.0.0.1) is trusted; "203.0.113.50" is attacker-suppliable
        // left-most padding, "70.41.3.18" is the right-most hop -- what the
        // trusted proxy actually saw as its peer, and therefore the real client.
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18");

        IncomingEvent event = service.receiveWebhook("validtoken", "{}", httpRequest);

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

        IncomingEvent result = service.receiveWebhook("validtoken", "{\"data\":1}", httpRequest);

        assertThat(result.getId()).isEqualTo(eventId);
        // No new event saved, no forwarding
        verify(eventRepository, never()).save(any(IncomingEvent.class));
        verify(forwardAttemptRepository, never()).saveAll(any());
    }

    @Test
    void receiveWebhook_newProviderEventId_setsFieldOnEvent() {
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
        when(httpRequest.getHeader("Stripe-Webhook-Id")).thenReturn("evt_stripe_456");

        IncomingEvent result = service.receiveWebhook("validtoken", "{}", httpRequest);

        assertThat(result.getProviderEventId()).isEqualTo("evt_stripe_456");
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
        // First call: dedup check returns empty (race window)
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "evt_race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        // save() throws DataIntegrityViolationException (unique index violation)
        when(eventRepository.save(any(IncomingEvent.class)))
                .thenThrow(new DataIntegrityViolationException("Unique index violation"));

        IncomingEvent result = service.receiveWebhook("validtoken", "{\"data\":1}", httpRequest);

        assertThat(result.getId()).isEqualTo(eventId);
        assertThat(result.getProviderEventId()).isEqualTo("evt_race");
        // No forwarding created
        verify(forwardAttemptRepository, never()).saveAll(any());
    }

    @Test
    void receiveWebhook_duplicateRace_noProviderEventId_rethrows() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        // No provider event ID header — providerEventId will be null (no body hash fallback)
        when(eventRepository.save(any(IncomingEvent.class)))
                .thenThrow(new DataIntegrityViolationException("Unique index violation"));

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{\"data\":1}", httpRequest))
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

        // Two events with identical body but no provider event ID header — both must be saved
        IncomingEvent first = service.receiveWebhook("validtoken", "{\"status\":\"active\"}", httpRequest);
        IncomingEvent second = service.receiveWebhook("validtoken", "{\"status\":\"active\"}", httpRequest);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getProviderEventId()).isNull();
        assertThat(second.getProviderEventId()).isNull();
        assertThat(first.getBodySha256()).isEqualTo(second.getBodySha256());
        verify(eventRepository, times(2)).save(any(IncomingEvent.class));
    }

    @Test
    void receiveWebhook_slackEventId_extractedFromBody() {
        IncomingSource source = buildActiveSource();
        String slackBody = "{\"event_id\":\"Ev0PV52K25\",\"event\":{\"type\":\"message\"}}";
        IncomingEvent existing = IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("old-req").method("POST")
                .providerEventId("Ev0PV52K25")
                .receivedAt(Instant.now())
                .build();

        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(httpRequest.getHeader("X-Slack-Signature")).thenReturn("v0=somesig");
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "Ev0PV52K25"))
                .thenReturn(Optional.of(existing));

        IncomingEvent result = service.receiveWebhook("validtoken", slackBody, httpRequest);

        assertThat(result.getId()).isEqualTo(eventId);
        assertThat(result.getProviderEventId()).isEqualTo("Ev0PV52K25");
        verify(eventRepository, never()).save(any(IncomingEvent.class));
    }

    @Test
    void receiveWebhook_slackNoEventId_noDedupOnTimestamp() {
        IncomingSource source = buildActiveSource();
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        when(eventRepository.save(any(IncomingEvent.class))).thenAnswer(inv -> {
            IncomingEvent e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        stubHttpRequest();
        // Slack url_verification challenge — no event_id in body
        when(httpRequest.getHeader("X-Slack-Signature")).thenReturn("v0=sig");
        when(httpRequest.getHeader("X-Slack-Request-Timestamp")).thenReturn("1531420618");

        String challengeBody = "{\"type\":\"url_verification\",\"challenge\":\"abc\"}";
        IncomingEvent result = service.receiveWebhook("validtoken", challengeBody, httpRequest);

        assertThat(result.getProviderEventId()).isNull();
        verify(eventRepository).save(any(IncomingEvent.class));
    }

    @Test
    void isSensitiveHeader_exactMatches() {
        assertThat(HeaderSanitizer.isSensitiveHeader("Authorization")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("cookie")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("Set-Cookie")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Api-Key")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("Proxy-Authorization")).isTrue();
    }

    @Test
    void isSensitiveHeader_patternMatches_providerSignatures() {
        assertThat(HeaderSanitizer.isSensitiveHeader("Stripe-Signature")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Hub-Signature-256")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Shopify-Hmac-SHA256")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Twilio-Signature")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Slack-Signature")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Webhook-Secret")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Auth-Token")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Access-Token")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Credential-Id")).isTrue();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Password-Hash")).isTrue();
    }

    @Test
    void isSensitiveHeader_safeHeaders_notMasked() {
        assertThat(HeaderSanitizer.isSensitiveHeader("Content-Type")).isFalse();
        assertThat(HeaderSanitizer.isSensitiveHeader("User-Agent")).isFalse();
        assertThat(HeaderSanitizer.isSensitiveHeader("Accept")).isFalse();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Request-Id")).isFalse();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-Webhook-Id")).isFalse();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-GitHub-Event")).isFalse();
        assertThat(HeaderSanitizer.isSensitiveHeader("X-GitHub-Delivery")).isFalse();
        assertThat(HeaderSanitizer.isSensitiveHeader("Host")).isFalse();
    }

    // -- transaction scope must cover only the writes, and a replay marker must not
    // survive a persist that never committed. --

    @Test
    void receiveWebhook_invalidToken_rejectedWithoutOpeningTransaction() {
        when(sourceRepository.findByIngressPathToken("invalid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receiveWebhook("invalid", "{}", httpRequest))
                .isInstanceOf(SourceNotFoundException.class);

        // An invalid-token request must never hold a Hikari connection -- no transaction
        // should have been opened for it at all.
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void receiveWebhook_disabledSource_rejectedWithoutOpeningTransaction() {
        IncomingSource source = buildActiveSource();
        source.setStatus(IncomingSourceStatus.DISABLED);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{}", httpRequest))
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

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{\"test\":true}", httpRequest))
                .isInstanceOf(SignatureVerificationFailedException.class);

        // Verification (and its Redis round trips) must complete before any DB transaction for
        // the write is opened -- a burst of these must not exhaust the connection pool.
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void receiveWebhook_failedPersistWithNoExistingRow_releasesReplayMarker() {
        // Reproduces: isReplay marks the signature as seen the moment it's checked
        // (before persisting anything). If the write that follows never commits and there's no
        // existing row to fall back to, the event is genuinely lost -- the marker must be
        // released, or the provider's legitimate resend of the exact same webhook is rejected
        // as a replay attack for the rest of the 5-minute TTL window instead of being retried.
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
        // First time seeing this signature -- isReplay marks it and returns false (not a replay).
        when(replayDetectionService.isReplay(eq(sourceId.toString()), eq(validHmac))).thenReturn(false);
        // No provider event ID header, so there is no dedup row to recover through, and the
        // persist fails outright (e.g. an unrelated constraint violation).
        when(eventRepository.save(any(IncomingEvent.class)))
                .thenThrow(new DataIntegrityViolationException("some other constraint violation"));

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", body, httpRequest))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The marker this exact request set must be released -- not burned for an event that
        // never made it to disk.
        verify(replayDetectionService).unmark(sourceId.toString(), validHmac);
    }

    @Test
    void receiveWebhook_duplicateRaceResolvedToExistingRow_doesNotReleaseReplayMarker() {
        // Counterpart to the above: when the race resolves to an existing row (the concurrent
        // winning request's own transaction already committed), the marker correctly belongs to
        // that persisted event and must NOT be released.
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

        IncomingEvent result = service.receiveWebhook("validtoken", body, httpRequest);

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

        IncomingEvent event = service.receiveWebhook("validtoken", body, httpRequest);

        assertThat(event.getId()).isEqualTo(eventId);
        verify(replayDetectionService, never()).unmark(any(), any());
    }

    private String computeHmac(String secret, String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Metering: the public ingress path is charged like any other ingest ────────────

    @Test
    void overTheMonthlyQuota_isRejectedWithoutPersistingAnything() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(buildActiveSource()));
        stubHttpRequest();
        doThrow(new QuotaExceededException("events_per_month", 1000, 1000, "Free"))
                .when(entitlementService).checkEventQuota();

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{}", httpRequest))
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

        service.receiveWebhook("validtoken", "{}", httpRequest);

        verify(entitlementService).checkEventQuota();
        verify(quotaCounterService).increment();
    }

    /**
     * The Incoming Event already existed, so nothing new was stored. Charging again would bill
     * the customer twice for one webhook the provider happened to send twice.
     */
    @Test
    void aDeduplicatedWebhookIsNotChargedAgain() {
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(buildActiveSource()));
        stubHttpRequest();
        when(httpRequest.getHeader("X-GitHub-Delivery")).thenReturn("gh-1");
        when(eventRepository.findByIncomingSourceIdAndProviderEventId(sourceId, "gh-1"))
                .thenReturn(Optional.of(IncomingEvent.builder().id(eventId).incomingSourceId(sourceId).build()));

        service.receiveWebhook("validtoken", "{}", httpRequest);

        verify(eventRepository, never()).save(any());
        verify(quotaCounterService, never()).increment();
    }

    // ── Rate limit: a Source that names no limit of its own still has one ─────────────

    @Test
    void aSourceWithNoRateLimitOfItsOwnFallsBackToTheConfiguredDefault() {
        IncomingSource source = buildActiveSource();
        source.setRateLimitPerSecond(null);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rateLimiterService.tryAcquireForSourceFailClosed(sourceId, DEFAULT_RATE_LIMIT)).thenReturn(true);

        service.receiveWebhook("validtoken", "{}", httpRequest);

        verify(rateLimiterService).tryAcquireForSourceFailClosed(sourceId, DEFAULT_RATE_LIMIT);
    }

    @Test
    void aSourceWithItsOwnRateLimitKeepsIt() {
        IncomingSource source = buildActiveSource();
        source.setRateLimitPerSecond(7);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(destinationRepository.findByIncomingSourceIdAndEnabledTrue(sourceId)).thenReturn(List.of());
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rateLimiterService.tryAcquireForSourceFailClosed(sourceId, 7)).thenReturn(true);

        service.receiveWebhook("validtoken", "{}", httpRequest);

        verify(rateLimiterService).tryAcquireForSourceFailClosed(sourceId, 7);
    }

    @Test
    void exceedingTheDefaultRateLimitRejectsTheWebhook() {
        IncomingSource source = buildActiveSource();
        source.setRateLimitPerSecond(null);
        when(sourceRepository.findByIngressPathToken("validtoken")).thenReturn(Optional.of(source));
        stubHttpRequest();
        when(rateLimiterService.tryAcquireForSourceFailClosed(sourceId, DEFAULT_RATE_LIMIT)).thenReturn(false);

        assertThatThrownBy(() -> service.receiveWebhook("validtoken", "{}", httpRequest))
                .isInstanceOf(RateLimitExceededException.class);
        verify(eventRepository, never()).save(any());
    }
}
