package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.ingress.HeaderSanitizer;
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
    private final WebhookVerifierFactory verifierFactory;
    private final ReplayDetectionService replayDetectionService;
    private final RedisRateLimiterService rateLimiterService;
    private final TrustedProxyResolver clientIpResolver;
    private final TransactionTemplate transactionTemplate;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final EntitlementService entitlementService;
    private final QuotaCounterService quotaCounterService;
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
        this.verifierFactory = verifierFactory;
        this.replayDetectionService = replayDetectionService;
        this.rateLimiterService = rateLimiterService;
        this.clientIpResolver = clientIpResolver;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.entitlementService = entitlementService;
        this.quotaCounterService = quotaCounterService;
        this.maxPayloadSizeBytes = maxPayloadSizeBytes;
        this.defaultRateLimitPerSecond = defaultRateLimitPerSecond;

        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Only the writes (IncomingEvent + forward attempts + outbox) run inside a
     * transaction. Token lookup, rate limiting, payload-size check, signature verification and
     * replay-marking all happen first, on plain (non-transactional) reads and Redis round
     * trips -- previously the whole method ran inside one transaction, so every invalid-token
     * or rate-limited request held a Hikari connection for the duration of two Redis calls that
     * never wrote anything (a cheap DoS on the connection pool).
     */
    public IncomingEvent receiveWebhook(String token, String body, HttpServletRequest request) {
        // Ingress has a tenant but no caller. Nothing has authenticated, so TenantContextFilter
        // left the scope unset and the path token in the URL is the only thing that names an
        // organization -- which means the lookup that finds it has to run without one. Everything
        // after it is confined to the Source's organization, so the writes below (IncomingEvent,
        // forward attempts, outbox) get the right tenant stamped on them by Hibernate.
        IncomingSource source = TenantContext.callAsSystem(() -> resolveActiveSource(token));
        return TenantContext.callAs(source.getOrganizationId(), () -> receiveVerifiedWebhook(source, body, request));
    }

    private IncomingEvent receiveVerifiedWebhook(IncomingSource source, String body, HttpServletRequest request) {
        enforceRateLimit(source);
        enforcePayloadSize(body);
        // An Incoming Event is an Event the Organization is charged for, same as one it posts to
        // /events itself. This runs inside the Source's tenant scope, which is the only thing on
        // this path that names an organization: ingress is unauthenticated, so the AuthContext
        // @RequireQuota resolves against does not exist here and the annotation would no-op.
        entitlementService.checkEventQuota();

        RequestMetadata meta = extractMetadata(body, request);
        VerificationOutcome verification = verifyAndCheckReplay(source, body, request);

        // Block immediately when signature verification is configured and not verified
        if (source.getVerificationMode() != VerificationMode.NONE && !Boolean.TRUE.equals(verification.verified())) {
            meterRegistry.counter("incoming_events_rejected_total",
                    "reason", "signature_verification_failed").increment();
            String reason = verification.verificationError() != null
                    ? verification.verificationError() : "Verification not completed";
            log.warn("Rejecting incoming webhook due to failed signature verification: sourceId={}, error={}",
                    source.getId(), reason);
            throw new SignatureVerificationFailedException("Signature verification failed: " + reason);
        }

        // Extract provider event ID for dedup (well-known headers only, no body hash fallback)
        String providerEventId = ProviderEventIdExtractor.extract(request, body);

        // Dedup: if same source + same provider event ID already exists, return existing
        // (idempotent). Plain read, no explicit transaction needed.
        if (providerEventId != null) {
            var existing = eventRepository.findByIncomingSourceIdAndProviderEventId(source.getId(), providerEventId);
            if (existing.isPresent()) {
                log.info("Duplicate incoming webhook detected: sourceId={}, providerEventId={}, existingEventId={}",
                        source.getId(), providerEventId, existing.get().getId());
                meterRegistry.counter("incoming_events_deduplicated_total").increment();
                return existing.get();
            }
        }

        try {
            IncomingEvent stored = transactionTemplate.execute(status ->
                    persistEventAndForwardAttempts(source, meta, providerEventId, verification));
            chargeQuotaPostCommit();
            return stored;
        } catch (DataIntegrityViolationException e) {
            IncomingEvent recovered = handleDuplicateRace(source, providerEventId, e);
            if (recovered != null) {
                return recovered;
            }
            // Genuinely lost -- nothing was persisted and there's no existing row to fall back
            // to. The replay marker (if any) must not stay burned for a webhook that never made
            // it to disk, or the provider's legitimate resend gets rejected as a replay for the
            // rest of the TTL window instead of just being retried.
            releaseReplayMarkerAfterFailedPersist(source, verification);
            throw e;
        } catch (RuntimeException e) {
            releaseReplayMarkerAfterFailedPersist(source, verification);
            throw e;
        }
    }

    private IncomingSource resolveActiveSource(String token) {
        IncomingSource source = sourceRepository.findByIngressPathToken(token)
                .orElseThrow(() -> new SourceNotFoundException("Invalid ingress token"));
        if (source.getStatus() != IncomingSourceStatus.ACTIVE) {
            throw new SourceDisabledException("Source is disabled");
        }
        return source;
    }

    /**
     * Charges the Organization for the Incoming Event that just committed.
     *
     * <p>Deliberately fire-and-forget and deliberately after the commit, for the reason
     * EventIngestService gives: the counter is an approximate Redis value that no rollback
     * undoes, so charging inside the transaction billed for ingests that never happened. A
     * webhook resolved by dedup never reaches here, because nothing new was stored.
     */
    private void chargeQuotaPostCommit() {
        try {
            quotaCounterService.increment();
        } catch (Exception e) {
            log.error("Failed to charge quota after a committed incoming webhook: {}", e.getMessage(), e);
        }
    }

    /**
     * Per-source rate limiting, fail-closed: reject if Redis is down.
     *
     * <p>A Source that names no limit of its own gets the configured default rather than none.
     * Until it did, the only thing standing between an unauthenticated {@code /ingress/{token}}
     * and the database was one platform-wide bucket shared by every tenant.
     */
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

    private void enforcePayloadSize(String body) {
        // Enforce size limit (measure in bytes, not characters — multi-byte UTF-8 matters)
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > maxPayloadSizeBytes) {
            throw new PayloadTooLargeException("Payload exceeds maximum allowed size of " + maxPayloadSizeBytes + " bytes");
        }
    }

    private record RequestMetadata(String requestId, String method, String path, String queryParams,
                                    String contentType, String clientIp, String userAgent,
                                    String headersJson, String bodySha256, String body) {
    }

    private RequestMetadata extractMetadata(String body, HttpServletRequest request) {
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
        return new RequestMetadata(requestId, method, path, queryParams, contentType, clientIp, userAgent,
                headersJson, bodySha256, body);
    }

    private record VerificationOutcome(Boolean verified, String verificationError, String replayKey) {
    }

    /**
     * Verifies the signature BEFORE dedup to prevent dedup poisoning (P0 security fix). An
     * attacker could send a webhook with a known providerEventId but invalid signature; if we
     * dedup/persist first, the poisoned record blocks the real webhook.
     *
     * <p>Unified replay detection for ALL verifiers (Generic, Stripe, GitHub, Slack, Shopify):
     * after successful verification, check if this exact signature was already seen. Key =
     * sourceId + SHA256(replayKey). TTL = 5 min (matches provider timestamp tolerance). The
     * check marks the signature as seen as a side effect -- if the write that's
     * supposed to follow never commits, the caller must release this mark via
     * {@link #releaseReplayMarkerAfterFailedPersist}.
     */
    private VerificationOutcome verifyAndCheckReplay(IncomingSource source, String body, HttpServletRequest request) {
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

        if (Boolean.TRUE.equals(verified) && replayKey != null) {
            if (replayDetectionService.isReplay(source.getId().toString(), replayKey)) {
                meterRegistry.counter("incoming_events_rejected_total",
                        "reason", "replay_detected").increment();
                log.warn("Replay attack detected for source {}", source.getId());
                throw new SignatureVerificationFailedException("Replay attack detected: signature already seen");
            }
        }

        return new VerificationOutcome(verified, verificationError, replayKey);
    }

    private void releaseReplayMarkerAfterFailedPersist(IncomingSource source, VerificationOutcome verification) {
        if (Boolean.TRUE.equals(verification.verified()) && verification.replayKey() != null) {
            replayDetectionService.unmark(source.getId().toString(), verification.replayKey());
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

    /**
     * Everything that must be transactional: persisting the IncomingEvent row and, if there are
     * enabled destinations, the forward-attempt + outbox rows in the same transaction as the
     * outbox pattern requires. Runs inside {@code transactionTemplate.execute} only -- no
     * Redis/verification work happens in here.
     */
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

        // Create forward attempts + outbox messages in batch
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

    private String computeSha256(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Failed to compute SHA-256: {}", e.getMessage());
            return null;
        }
    }
}
