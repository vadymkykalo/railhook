package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.security.SuspensionCheck;
import com.webhook.platform.common.demo.DemoTenant;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.ingress.HeaderSanitizer;
import com.webhook.platform.api.service.ingress.IngressOutcome;
import com.webhook.platform.api.service.ingress.OrganizationSuspendedException;
import com.webhook.platform.api.service.ingress.PayloadTooLargeException;
import com.webhook.platform.api.service.ingress.ProviderEventIdExtractor;
import com.webhook.platform.api.service.ingress.RateLimitExceededException;
import com.webhook.platform.api.service.ingress.SignatureVerificationFailedException;
import com.webhook.platform.api.service.ingress.SourceDisabledException;
import com.webhook.platform.api.service.ingress.SourceNotFoundException;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.service.billing.QuotaCounterService;
import com.webhook.platform.api.service.verification.ReplayDetectionService;
import com.webhook.platform.api.service.verification.WebhookVerificationStrategy;
import com.webhook.platform.api.service.verification.WebhookVerifierFactory;
import com.webhook.platform.common.enums.VerificationMode;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;

@Service
@Slf4j
public class IngressService {

    private final IncomingSourceRepository sourceRepository;
    private final IncomingEventRepository eventRepository;
    private final IncomingDestinationRepository destinationRepository;
    private final IncomingForwardAttemptRepository forwardAttemptRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final ObjectMapper objectMapper;
    private final ForwardDispatch forwardDispatch;
    private final MeterRegistry meterRegistry;
    private final Counter incomingEventsIngestedCounter;
    private final WebhookVerifierFactory verifierFactory;
    private final ReplayDetectionService replayDetectionService;
    private final RedisRateLimiterService rateLimiterService;
    private final TrustedProxyResolver clientIpResolver;
    private final TransactionTemplate transactionTemplate;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final EntitlementService entitlementService;
    private final QuotaCounterService quotaCounterService;
    private final ProjectRepository projectRepository;
    private final SuspensionCheck suspensionCheck;
    private final long maxPayloadSizeBytes;
    private final int defaultRateLimitPerSecond;

    public IngressService(
            IncomingSourceRepository sourceRepository,
            IncomingEventRepository eventRepository,
            IncomingDestinationRepository destinationRepository,
            IncomingForwardAttemptRepository forwardAttemptRepository,
            OutboxMessageRepository outboxMessageRepository,
            ObjectMapper objectMapper,
            ForwardDispatch forwardDispatch,
            MeterRegistry meterRegistry,
            WebhookVerifierFactory verifierFactory,
            ReplayDetectionService replayDetectionService,
            RedisRateLimiterService rateLimiterService,
            TrustedProxyResolver clientIpResolver,
            PlatformTransactionManager transactionManager,
            EncryptionKeyRegistry encryptionKeyRegistry,
            EntitlementService entitlementService,
            QuotaCounterService quotaCounterService,
            ProjectRepository projectRepository,
            SuspensionCheck suspensionCheck,
            @Value("${webhook.incoming.max-payload-size-bytes:524288}") long maxPayloadSizeBytes,
            @Value("${webhook.incoming.rate-limit-per-second:100}") int defaultRateLimitPerSecond) {
        this.sourceRepository = sourceRepository;
        this.eventRepository = eventRepository;
        this.destinationRepository = destinationRepository;
        this.forwardAttemptRepository = forwardAttemptRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.objectMapper = objectMapper;
        this.forwardDispatch = forwardDispatch;
        this.meterRegistry = meterRegistry;
        // Shared with the /events path. Registered eagerly so an idle deployment exports 0.
        this.incomingEventsIngestedCounter = Counter.builder("events_ingested_total").tag("direction", "incoming")
                .description("Events accepted, by the direction they travel").register(meterRegistry);
        this.verifierFactory = verifierFactory;
        this.replayDetectionService = replayDetectionService;
        this.rateLimiterService = rateLimiterService;
        this.clientIpResolver = clientIpResolver;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.entitlementService = entitlementService;
        this.quotaCounterService = quotaCounterService;
        this.projectRepository = projectRepository;
        this.suspensionCheck = suspensionCheck;
        this.maxPayloadSizeBytes = maxPayloadSizeBytes;
        this.defaultRateLimitPerSecond = defaultRateLimitPerSecond;

        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // No transaction across the Redis checks, or rejected requests each pin a pool connection.
    public IngressOutcome receiveWebhook(String token, byte[] body, HttpServletRequest request) {
        // The path token is the only thing naming an organization, so this lookup runs as system.
        IncomingSource source = TenantContext.callAsSystem(() -> resolveActiveSource(token));
        return TenantContext.callAs(source.getOrganizationId(), () -> receiveVerifiedWebhook(source, body, request));
    }

    private IngressOutcome receiveVerifiedWebhook(IncomingSource source, byte[] body, HttpServletRequest request) {
        enforceRateLimit(source);
        enforcePayloadSize(body);

        RequestMetadata meta = extractMetadata(body, request);
        VerificationOutcome verification = verify(source, body, request);

        if (source.getVerificationMode() != VerificationMode.NONE && !Boolean.TRUE.equals(verification.verified())) {
            meterRegistry.counter("incoming_events_rejected_total",
                    "reason", "signature_verification_failed").increment();
            String reason = verification.verificationError() != null
                    ? verification.verificationError() : "Verification not completed";
            log.warn("Rejecting incoming webhook due to failed signature verification: sourceId={}, error={}",
                    source.getId(), reason);
            throw new SignatureVerificationFailedException("Signature verification failed: " + reason);
        }

        // A handshake, never stored. Echoed only when verified, so nobody can claim another's URL.
        if (source.getProviderType() == ProviderType.SLACK && Boolean.TRUE.equals(verification.verified())) {
            String challenge = slackUrlVerificationChallenge(meta.body());
            if (challenge != null) {
                log.info("Answered Slack url_verification: sourceId={}", source.getId());
                return new IngressOutcome.SlackUrlVerification(challenge);
            }
        }

        String providerEventId = ProviderEventIdExtractor.extract(request, meta.body());

        // Before quota and replay checks, so a same-signature resend is not refused; after
        // verification, so a forged request cannot probe which ids exist.
        if (providerEventId != null) {
            var existing = eventRepository.findByIncomingSourceIdAndProviderEventId(source.getId(), providerEventId);
            if (existing.isPresent()) {
                log.info("Duplicate incoming webhook detected: sourceId={}, providerEventId={}, existingEventId={}",
                        source.getId(), providerEventId, existing.get().getId());
                meterRegistry.counter("incoming_events_deduplicated_total").increment();
                return new IngressOutcome.Accepted(existing.get());
            }
        }

        // By hand, since ingress has no AuthContext; before the replay check so a retry is not a replay.
        entitlementService.checkEventQuota();
        rejectReplay(source, verification, providerEventId);

        try {
            IncomingEvent stored = transactionTemplate.execute(status ->
                    persistEventAndForwardAttempts(source, meta, providerEventId, verification));
            chargeQuotaPostCommit();
            incomingEventsIngestedCounter.increment();
            return new IngressOutcome.Accepted(stored);
        } catch (DataIntegrityViolationException e) {
            IncomingEvent recovered = handleDuplicateRace(source, providerEventId, e);
            if (recovered != null) {
                return new IngressOutcome.Accepted(recovered);
            }
            // Nothing was stored, so the replay marker must not block the provider's resend.
            releaseReplayMarkerAfterFailedPersist(source, verification, providerEventId);
            throw e;
        } catch (RuntimeException e) {
            releaseReplayMarkerAfterFailedPersist(source, verification, providerEventId);
            throw e;
        }
    }

    private String slackUrlVerificationChallenge(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode type = root.get("type");
            JsonNode challenge = root.get("challenge");
            if (type != null && "url_verification".equals(type.asText())
                    && challenge != null && challenge.isTextual() && !challenge.asText().isEmpty()) {
                return challenge.asText();
            }
        } catch (Exception e) {
            log.debug("Slack body is not JSON, so not a url_verification: {}", e.getMessage());
        }
        return null;
    }

    private IncomingSource resolveActiveSource(String token) {
        IncomingSource source = sourceRepository.findByIngressPathToken(token)
                .orElseThrow(() -> new SourceNotFoundException("Invalid ingress token"));
        // The Source row outlives a deleted project; its address must not.
        if (!projectRepository.existsById(source.getProjectId())) {
            throw new SourceNotFoundException("Invalid ingress token");
        }
        if (source.getStatus() != IncomingSourceStatus.ACTIVE) {
            throw new SourceDisabledException("Source is disabled");
        }
        // The suspension interceptor never sees this unauthenticated path.
        if (suspensionCheck.suspensionReason(source.getOrganizationId()).isPresent()) {
            log.warn("Rejecting incoming webhook: organization {} is suspended (sourceId={})",
                    source.getOrganizationId(), source.getId());
            throw new OrganizationSuspendedException("Organization is suspended");
        }
        // Demo ingress URLs are public; accepting on them would let anyone write into the demo.
        if (DemoTenant.isDemoOrganization(source.getOrganizationId())) {
            throw new OrganizationSuspendedException("The demo organization does not take webhooks");
        }
        return source;
    }

    // After commit: the Redis counter is not rolled back with the transaction.
    private void chargeQuotaPostCommit() {
        try {
            quotaCounterService.increment();
        } catch (Exception e) {
            log.error("Failed to charge quota after a committed incoming webhook: {}", e.getMessage(), e);
        }
    }

    /** Fail-closed. A Source without its own limit gets the default, never none. */
    private void enforceRateLimit(IncomingSource source) {
        int limit = source.getRateLimitPerSecond() != null && source.getRateLimitPerSecond() > 0
                ? source.getRateLimitPerSecond()
                : defaultRateLimitPerSecond;
        if (limit <= 0) {
            return;
        }
        if (!rateLimiterService.tryAcquireForSourceFailClosed(source.getId(), limit)) {
            throw new RateLimitExceededException("Rate limit exceeded for source " + source.getId());
        }
    }

    private void enforcePayloadSize(byte[] body) {
        if (body != null && body.length > maxPayloadSizeBytes) {
            throw new PayloadTooLargeException("Payload exceeds maximum allowed size of " + maxPayloadSizeBytes + " bytes");
        }
    }

    private record RequestMetadata(String requestId, String method, String path, String queryParams,
                                    String contentType, String clientIp, String userAgent,
                                    String headersJson, String bodySha256, String body, byte[] bodyBytes) {
    }

    // Raw bytes are also kept when text cannot hold them: invalid UTF-8, or a NUL.
    private RequestMetadata extractMetadata(byte[] body, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String queryParams = request.getQueryString();
        String contentType = request.getContentType();
        String clientIp = clientIpResolver.resolve(request);
        String rawUserAgent = request.getHeader("User-Agent");
        String userAgent = rawUserAgent != null && rawUserAgent.length() > 512
                ? rawUserAgent.substring(0, 512) : rawUserAgent;
        String headersJson = HeaderSanitizer.toJson(request, objectMapper);
        String bodySha256 = computeSha256(body);

        String storedBody = null;
        byte[] bodyBytes = null;
        if (body != null) {
            storedBody = decodeUtf8Exactly(body);
            if (storedBody == null || storedBody.indexOf('\u0000') >= 0) {
                bodyBytes = body;
                storedBody = new String(body, StandardCharsets.UTF_8).replace('\u0000', '\uFFFD');
            }
        }
        return new RequestMetadata(requestId, method, path, queryParams, contentType, clientIp, userAgent,
                headersJson, bodySha256, storedBody, bodyBytes);
    }

    /** Null when the body is not valid UTF-8. */
    private static String decodeUtf8Exactly(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private record VerificationOutcome(Boolean verified, String verificationError, String replayKey) {
    }

    // Before dedup, or a forged request with a known event id could poison it. Marks nothing.
    private VerificationOutcome verify(IncomingSource source, byte[] body, HttpServletRequest request) {
        Boolean verified = null;
        String verificationError = null;
        String replayKey = null;
        WebhookVerificationStrategy verifier = verifierFactory.getVerifier(source);
        if (verifier != null) {
            try {
                String secret = decryptHmacSecret(source);
                WebhookVerificationStrategy.VerificationResult result = verifier.verify(secret, body, request);
                verified = result.verified();
                replayKey = result.replayKey();
                if (!result.verified()) {
                    verificationError = result.error();
                }
            } catch (Exception e) {
                verified = false;
                verificationError = "Verification error: " + e.getMessage();
                log.warn("Webhook verification failed for source {}: {}", source.getId(), e.getMessage());
            }
        }

        return new VerificationOutcome(verified, verificationError, replayKey);
    }

    // Includes the delivery id: two genuine identical bodies share a signature.
    private static String replayKey(VerificationOutcome verification, String providerEventId) {
        return providerEventId == null ? verification.replayKey() : verification.replayKey() + ":" + providerEventId;
    }

    // Marks the signature as seen. If the write never commits, the caller must release the mark.
    private void rejectReplay(IncomingSource source, VerificationOutcome verification, String providerEventId) {
        if (Boolean.TRUE.equals(verification.verified()) && verification.replayKey() != null
                && replayDetectionService.isReplay(source.getId().toString(), replayKey(verification, providerEventId))) {
            meterRegistry.counter("incoming_events_rejected_total",
                    "reason", "replay_detected").increment();
            log.warn("Replay attack detected for source {}", source.getId());
            throw new SignatureVerificationFailedException("Replay attack detected: signature already seen");
        }
    }

    private void releaseReplayMarkerAfterFailedPersist(IncomingSource source, VerificationOutcome verification,
                                                        String providerEventId) {
        if (Boolean.TRUE.equals(verification.verified()) && verification.replayKey() != null) {
            replayDetectionService.unmark(source.getId().toString(), replayKey(verification, providerEventId));
            log.warn("Released replay marker after failed persist so a legitimate resend is not "
                    + "permanently rejected: sourceId={}", source.getId());
        }
    }

    private IncomingEvent handleDuplicateRace(IncomingSource source, String providerEventId,
                                               DataIntegrityViolationException e) {
        if (providerEventId != null) {
            var existing = eventRepository.findByIncomingSourceIdAndProviderEventId(source.getId(), providerEventId);
            if (existing.isPresent()) {
                log.info("Duplicate race resolved for incoming webhook: sourceId={}, providerEventId={}, existingEventId={}",
                        source.getId(), providerEventId, existing.get().getId());
                meterRegistry.counter("incoming_events_deduplicated_total").increment();
                return existing.get();
            }
        }
        return null;
    }

    private IncomingEvent persistEventAndForwardAttempts(IncomingSource source, RequestMetadata meta,
                                                           String providerEventId, VerificationOutcome verification) {
        IncomingEvent event = IncomingEvent.builder()
                .incomingSourceId(source.getId())
                .requestId(meta.requestId())
                .method(meta.method())
                .path(meta.path())
                .queryParams(meta.queryParams())
                .headersJson(meta.headersJson())
                .bodyRaw(meta.body())
                .bodyBytes(meta.bodyBytes())
                .bodySha256(meta.bodySha256())
                .providerEventId(providerEventId)
                .contentType(meta.contentType())
                .clientIp(meta.clientIp())
                .userAgent(meta.userAgent())
                .verified(verification.verified())
                .verificationError(verification.verificationError())
                .receivedAt(Instant.now())
                .build();

        event = eventRepository.save(event);

        meterRegistry.counter("incoming_events_received_total",
                "provider_type", source.getProviderType().name()).increment();

        log.info("Received incoming webhook: eventId={}, sourceId={}, requestId={}, verified={}",
                event.getId(), source.getId(), meta.requestId(), verification.verified());

        List<IncomingDestination> destinations = destinationRepository
                .findByIncomingSourceIdAndEnabledTrue(source.getId());

        if (!destinations.isEmpty()) {
            List<IncomingForwardAttempt> attempts = new ArrayList<>(destinations.size());
            List<OutboxMessage> outboxMessages = new ArrayList<>(destinations.size());

            for (IncomingDestination destination : destinations) {
                attempts.add(IncomingForwardAttempt.builder()
                        .incomingEventId(event.getId())
                        .destinationId(destination.getId())
                        .attemptNumber(1)
                        .status(ForwardAttemptStatus.PENDING)
                        .build());

                outboxMessages.add(forwardDispatch.outboxFor(event.getId(), source.getId(),
                        destination.getId(), source.getProjectId(), 0, null,
                        ForwardDispatch.Reason.CREATED));
            }

            forwardAttemptRepository.saveAll(attempts);
            outboxMessageRepository.saveAll(outboxMessages);
        }

        return event;
    }

    private String decryptHmacSecret(IncomingSource source) {
        if (source.getHmacSecretEncrypted() == null || source.getHmacSecretIv() == null) {
            throw new IllegalStateException("HMAC secret not configured for source " + source.getId());
        }
        return encryptionKeyRegistry.decryptWithFallback(
                source.getHmacSecretEncrypted(),
                source.getHmacSecretIv(),
                source.getEncryptionKeyVersion()
        );
    }

    private String computeSha256(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Failed to compute SHA-256: {}", e.getMessage());
            return null;
        }
    }
}
