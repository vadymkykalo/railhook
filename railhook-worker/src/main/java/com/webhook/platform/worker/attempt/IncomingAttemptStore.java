package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.retry.RetryableStatuses;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.HeaderSanitizer;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.worker.service.PayloadTransformException;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.TransformationCacheService;
import com.webhook.platform.common.transform.TransformRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Incoming writes one {@code incoming_forward_attempts} row per Attempt and inserts the successor
 * on a retryable finalisation. {@link OutgoingAttemptStore} mutates one row in place instead; both
 * models are public through DTOs and the dashboard, so neither can move to the other's.
 *
 * <p>One instance per Attempt; thread-confined.
 */
@Slf4j
public class IncomingAttemptStore implements AttemptStore<IncomingAttemptStore.Claim> {

    private static final int REQUEST_BODY_SNIPPET_LIMIT = 10240;

    /** Event metadata only, in canonical case. Never a provider signature or token. */
    private static final List<String> FORWARDED_PROVIDER_HEADERS = List.of(
            "X-GitHub-Event",
            "X-GitHub-Delivery",
            "X-GitHub-Hook-ID",
            "X-GitHub-Hook-Installation-Target-Type",
            "X-GitHub-Hook-Installation-Target-ID",
            "X-Gitlab-Event",
            "X-Gitlab-Event-UUID",
            "X-Gitlab-Instance",
            "X-Shopify-Topic",
            "X-Shopify-Webhook-Id",
            "X-Shopify-Shop-Domain",
            "X-Shopify-API-Version",
            "X-Shopify-Event-Id",
            "X-Shopify-Triggered-At");

    /**
     * {@code fence} is null only for a retry message published before claim tokens existed.
     * {@code replaySessionId} is part of the row's identity: once a Replay starts a second ladder,
     * (event, destination, attempt number) alone matches two rows.
     */
    public record Claim(UUID eventId, UUID destinationId, int attemptNumber, UUID fence,
            UUID replaySessionId) {
    }

    private final IncomingForwardAttemptRepository attemptRepository;
    private final ProjectStatusLookup projectStatusLookup;
    private final TransactionTemplate transactionTemplate;
    private final TransformationCacheService transformationCacheService;
    private final PayloadTransformService payloadTransformService;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate;
    private final TargetFailureRecorder targetFailureRecorder;

    private final IncomingForwardMessage message;
    private final IncomingEvent event;
    private final IncomingDestination destination;

    public IncomingAttemptStore(
            IncomingForwardAttemptRepository attemptRepository,
            ProjectStatusLookup projectStatusLookup,
            TransactionTemplate transactionTemplate,
            TransformationCacheService transformationCacheService,
            PayloadTransformService payloadTransformService,
            EncryptionKeyRegistry encryptionKeyRegistry,
            ObjectMapper objectMapper,
            WebClient webClient,
            KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate,
            TargetFailureRecorder targetFailureRecorder,
            IncomingForwardMessage message,
            IncomingEvent event,
            IncomingDestination destination) {
        this.attemptRepository = attemptRepository;
        this.projectStatusLookup = projectStatusLookup;
        this.transactionTemplate = transactionTemplate;
        this.transformationCacheService = transformationCacheService;
        this.payloadTransformService = payloadTransformService;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.objectMapper = objectMapper;
        this.webClient = webClient;
        this.kafkaTemplate = kafkaTemplate;
        this.targetFailureRecorder = targetFailureRecorder;
        this.message = message;
        this.event = event;
        this.destination = destination;
    }

    /**
     * First dispatch and replay claim a PENDING row outright. A retry CASes on the token the
     * scheduler stamped, because a redelivered Kafka message would otherwise double-POST.
     */
    @Override
    public ClaimResult<Claim> claim() {
        boolean isRetry = message.getAttemptCount() != null && message.getAttemptCount() > 0;
        boolean isReplay = message.isReplay();

        int attemptNumber = isRetry ? message.getAttemptCount() : 1;

        UUID claimToken = UUID.randomUUID();

        if (isRetry && !isReplay) {
            Instant expected = message.getStartedAt();
            if (expected == null) {
                log.debug("Retry message has no fencing token (older producer?), proceeding without CAS: "
                        + "eventId={}, destId={}, attempt={}", event.getId(), destination.getId(), attemptNumber);
                return claimed(attemptNumber, null);
            }
            Integer applied = transactionTemplate.execute(tx -> attemptRepository.claimRetryForProcessing(
                    event.getId(), destination.getId(), attemptNumber, message.getReplaySessionId(),
                    expected, claimToken));
            if (applied == null || applied == 0) {
                return new ClaimResult.NotClaimed<>(
                        "retry attempt already claimed by a prior delivery of this Kafka message");
            }
            // Fence on the new token, not on started_at, which this CAS just superseded.
            return claimed(attemptNumber, claimToken);
        }

        final int number = attemptNumber;
        Integer applied = transactionTemplate.execute(tx -> attemptRepository.claimForProcessing(
                event.getId(), destination.getId(), number, message.getReplaySessionId(), claimToken));
        if (applied == null || applied == 0) {
            return new ClaimResult.NotClaimed<>("forward attempt already claimed or not PENDING");
        }
        return claimed(number, claimToken);
    }

    /**
     * Admissibility checks run after the claim so that failing a Forward is written under the
     * fencing token. Checking before the claim used to write FAILED over a row someone else owned.
     */
    private ClaimResult<Claim> claimed(int attemptNumber, UUID fence) {
        Claim claim = new Claim(event.getId(), destination.getId(), attemptNumber, fence,
                message.getReplaySessionId());

        if (!Boolean.TRUE.equals(destination.getEnabled())) {
            // Turned off by its owner: fail. Auto-disabled by us: DLQ, so a person can retry.
            if (destination.getAutoDisabledAt() != null) {
                return abandoned(claim, "Destination auto-disabled: "
                        + reasonOrDefault(destination.getAutoDisabledReason()));
            }
            return terminal(claim, "Destination is disabled");
        }
        // Deleting a Project or suspending an Organization leaves its Destinations looking live.
        ProjectStatusLookup.ProjectStatus projectStatus =
                projectStatusLookup.forSource(destination.getIncomingSourceId());
        if (projectStatus == ProjectStatusLookup.ProjectStatus.DELETED) {
            return terminal(claim, "Project has been deleted");
        }
        if (projectStatus == ProjectStatusLookup.ProjectStatus.ORGANIZATION_SUSPENDED) {
            return deferred(claim, "Organization is suspended");
        }

        RetryLadder ladder;
        try {
            ladder = RetryLadder.parse(destination.getRetryDelays(), destination.getMaxAttempts());
        } catch (IllegalArgumentException e) {
            // The api rejects a malformed ladder, so this column was written outside it.
            log.error("Destination {} carries an unusable retry ladder: {}", destination.getId(), e.getMessage());
            return terminal(claim, "INVALID_RETRY_LADDER: " + e.getMessage());
        }

        RetryableStatuses retryableStatuses;
        try {
            retryableStatuses = RetryableStatuses.parse(destination.getRetryableStatuses());
        } catch (IllegalArgumentException e) {
            log.error("Destination {} carries an unusable retryable-status spec: {}",
                    destination.getId(), e.getMessage());
            return terminal(claim, "INVALID_RETRYABLE_STATUSES: " + e.getMessage());
        }

        AttemptContext context = new AttemptContext(
                "forward eventId=" + event.getId() + " destId=" + destination.getId()
                        + " attempt=" + attemptNumber + "/" + destination.getMaxAttempts(),
                event.getIncomingSourceId(),
                destination.getId(),
                null, // Destinations carry no per-target rate limit of their own
                attemptNumber,
                ladder,
                retryableStatuses,
                destination.getUrl(),
                AttemptSupport.clampTimeout(destination.getTimeoutSeconds()));
        return new ClaimResult.Claimed<>(claim, context);
    }

    private ClaimResult<Claim> deferred(Claim claim, String reason) {
        Instant until = Instant.now().plus(ProjectStatusLookup.SUSPENSION_RECHECK);
        log.info("Forward eventId={}, destId={} will not be attempted before {}: {}",
                claim.eventId(), claim.destinationId(), until, reason);
        finalise(claim, new Finalization.Deferred(until, reason));
        return new ClaimResult.Deferred<>(until, reason);
    }

    /** Never reaches {@link AttemptRunner}, so it runs the side effect itself if finalise applied. */
    private ClaimResult<Claim> abandoned(Claim claim, String reason) {
        log.warn("Forward eventId={}, destId={} will not be attempted and goes to Failed Messages: {}",
                claim.eventId(), claim.destinationId(), reason);
        if (finalise(claim, new Finalization.Abandoned(reason))) {
            onAbandoned(claim);
        }
        return new ClaimResult.NotClaimed<>(reason);
    }

    private static String reasonOrDefault(String reason) {
        return reason != null && !reason.isBlank() ? reason : "continuous failure";
    }

    /** Never reaches {@link AttemptRunner}, so it runs the release itself if finalise applied. */
    private ClaimResult<Claim> terminal(Claim claim, String reason) {
        log.warn("Forward eventId={}, destId={} will not be attempted: {}",
                claim.eventId(), claim.destinationId(), reason);
        if (finalise(claim, new Finalization.TerminallyFailed(reason))) {
            onTerminallyFailed(claim);
        }
        return new ClaimResult.NotClaimed<>(reason);
    }

    @Override
    public RequestSpec buildRequest(Claim claim, TransformedBody transformed) {
        String contentType = event.getContentType() != null ? event.getContentType() : "application/json";
        String idempotencyKey = event.getId() + "-" + destination.getId();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        if (!isTransformed()) {
            // Untransformed bytes are forwarded as received, e.g. still gzipped, so the encoding
            // header must go with them.
            String contentEncoding = arrivedHeader("Content-Encoding");
            if (contentEncoding != null) {
                headers.put("Content-Encoding", contentEncoding);
            }
        }
        headers.put("X-Incoming-Event-Id", event.getId().toString());
        if (event.getRequestId() != null) {
            headers.put("X-Incoming-Request-Id", event.getRequestId());
        }
        headers.put("X-Forward-Attempt", String.valueOf(claim.attemptNumber()));
        headers.put("Idempotency-Key", idempotencyKey);
        // Script headers go before credentials and custom headers: a script may add, not impersonate.
        headers.putAll(transformed.headers());
        new DestinationAuthenticator(destination, encryptionKeyRegistry, objectMapper).authenticate(headers);
        AttemptSupport.collectCustomHeaders(headers, destination.getCustomHeadersJson(), objectMapper);
        // Last, so anything set above wins.
        forwardProviderEventHeaders(headers);

        return new RequestSpec(webClient, request -> headers.forEach(request::header), recorded(headers));
    }

    /** The event keeps raw bytes only when its text copy cannot reproduce them. */
    @Override
    public byte[] wireBody(Claim claim, String body) {
        if (!isTransformed() && event.getBodyBytes() != null) {
            return event.getBodyBytes();
        }
        return AttemptStore.super.wireBody(claim, body);
    }

    private boolean isTransformed() {
        return destination.getTransformationId() != null
                || (destination.getPayloadTransform() != null && !destination.getPayloadTransform().isBlank());
    }

    /**
     * Some providers name the event only in a header (GitHub push and issue bodies look alike),
     * so the Destination needs these to route. An allowlist, not a pass-through: provider
     * signatures prove the request to us, not to the Destination. Values with control characters
     * are dropped to prevent header injection.
     */
    private void forwardProviderEventHeaders(Map<String, String> headers) {
        Map<String, String> arrived = arrivedHeaders();
        if (arrived.isEmpty()) {
            return;
        }
        for (String name : FORWARDED_PROVIDER_HEADERS) {
            String value = headerIgnoringCase(arrived, name);
            if (value == null || containsControlCharacter(value) || headerIgnoringCase(headers, name) != null) {
                continue;
            }
            headers.put(name, value);
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static String headerIgnoringCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    /** For a script's {@code webhook.eventType}. Null when the provider sent no event header. */
    private String providerEventType() {
        Map<String, String> arrived = arrivedHeaders();
        for (String name : FORWARDED_PROVIDER_HEADERS) {
            String value = headerIgnoringCase(arrived, name);
            if (value != null && !value.isBlank() && !containsControlCharacter(value)) {
                return value;
            }
        }
        return null;
    }

    private String arrivedHeader(String name) {
        return headerIgnoringCase(arrivedHeaders(), name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> arrivedHeaders() {
        if (event.getHeadersJson() == null) {
            return Map.of();
        }
        try {
            Map<String, String> arrived = objectMapper.readValue(event.getHeadersJson(), Map.class);
            return arrived != null ? arrived : Map.of();
        } catch (Exception e) {
            log.warn("Could not read the stored request headers of incoming event {}: {}",
                    event.getId(), e.getMessage());
            return Map.of();
        }
    }

    /** Masks the Destination's credentials before they reach the dashboard. */
    private String recorded(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(HeaderSanitizer.sanitize(headers));
        } catch (Exception e) {
            log.warn("Failed to serialise forward request headers: {}", e.getMessage());
            return null;
        }
    }

    /**
     * A configured transformation that fails to apply fails the Attempt instead of forwarding the
     * raw body, because transformations are how PII is stripped before relaying.
     */
    @Override
    public TransformedBody buildBody(Claim claim) {
        String body = event.getBodyRaw();
        if (body == null || body.isBlank()) {
            return TransformedBody.of(body);
        }

        if (destination.getTransformationId() != null) {
            TransformationCacheService.Resolved resolved =
                    transformationCacheService.findEnabled(destination.getTransformationId());
            if (resolved == null) {
                throw new PayloadTransformException(
                        "Configured transformation " + destination.getTransformationId()
                                + " not found or disabled for destination " + destination.getId());
            }
            return payloadTransformService.apply(resolved, body, TransformRequest.builder()
                    .payload(body)
                    .eventType(providerEventType())
                    .eventId(event.getId().toString())
                    .timestamp(event.getReceivedAt())
                    .direction("INCOMING")
                    .url(destination.getUrl())
                    // Credentials are added later and are never shown to the script.
                    .headers(configuredHeaders())
                    .attemptNumber(claim.attemptNumber())
                    .build());
        }

        String inline = destination.getPayloadTransform();
        if (inline == null || inline.isBlank()) {
            return TransformedBody.of(body);
        }
        try {
            Object result = JsonPath.read(body, inline);
            if (result instanceof String s) {
                return TransformedBody.of(s);
            }
            return TransformedBody.of(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new PayloadTransformException(
                    "Inline payload transform failed for destination " + destination.getId() + ": " + e.getMessage(), e);
        }
    }

    private Map<String, String> configuredHeaders() {
        Map<String, String> configured = new LinkedHashMap<>();
        AttemptSupport.collectCustomHeaders(configured, destination.getCustomHeadersJson(), objectMapper);
        return configured;
    }

    @Override
    public void attemptStarting(Claim claim) {
    }

    @Override
    public void recordAttempt(Claim claim, AttemptRecord record) {
        this.pendingRecord = record;
    }

    private AttemptRecord pendingRecord;

    /** Only while the row is still PROCESSING: a late writer used to overwrite a terminal row. */
    @Override
    public boolean finalise(Claim claim, Finalization outcome) {
        Boolean applied = transactionTemplate.execute(tx -> {
            IncomingForwardAttempt attempt = findAttempt(claim);
            if (attempt == null) {
                log.error("Attempt row not found for finalisation: eventId={}, destId={}, attempt={}",
                        claim.eventId(), claim.destinationId(), claim.attemptNumber());
                return false;
            }

            if (outcome instanceof Finalization.Deferred deferred) {
                // next_retry_at must be set: the scheduler ignores rows without one.
                if (attempt.getStatus() != ForwardAttemptStatus.PENDING
                        && attempt.getStatus() != ForwardAttemptStatus.PROCESSING) {
                    return false;
                }
                if (!stillHoldsClaim(claim, attempt,
                        ForwardAttemptStatus.PENDING, ForwardAttemptStatus.PROCESSING)) {
                    return false;
                }
                attempt.handBackTo(deferred.until());
                applyRecord(attempt);
                attemptRepository.save(attempt);
                return true;
            }

            if (attempt.getStatus() != ForwardAttemptStatus.PROCESSING) {
                log.warn("Attempt {} for eventId={}, destId={} is already {} — refusing to overwrite",
                        claim.attemptNumber(), claim.eventId(), claim.destinationId(), attempt.getStatus());
                return false;
            }

            if (!stillHoldsClaim(claim, attempt, ForwardAttemptStatus.PROCESSING)) {
                log.warn("Attempt {} for eventId={}, destId={} was reclaimed while this attempt was in "
                                + "flight — refusing to finalise a row another attempt now owns",
                        claim.attemptNumber(), claim.eventId(), claim.destinationId());
                return false;
            }

            attempt.setStatus(statusFor(outcome));
            attempt.setFinishedAt(Instant.now());
            attempt.setNextRetryAt(null);
            applyRecord(attempt);
            attempt.setErrorMessage(reasonFor(outcome));
            attemptRepository.save(attempt);

            if (outcome instanceof Finalization.Retry retry) {
                attemptRepository.save(IncomingForwardAttempt.builder()
                        .incomingEventId(claim.eventId())
                        .destinationId(claim.destinationId())
                        .organizationId(attempt.getOrganizationId())
                        .attemptNumber(claim.attemptNumber() + 1)
                        .replaySessionId(claim.replaySessionId())
                        .status(ForwardAttemptStatus.PENDING)
                        .nextRetryAt(retry.at())
                        .build());
            }
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    /**
     * Re-checked under the row lock because the read was a snapshot. A stuck sweep that committed
     * in between used to be overwritten, and a Retry queued a second successor.
     */
    private boolean stillHoldsClaim(Claim claim, IncomingForwardAttempt attempt, ForwardAttemptStatus... statuses) {
        if (!AttemptSupport.fenceMatches(attempt.getClaimToken(), claim.fence())) {
            return false;
        }
        List<String> names = Arrays.stream(statuses).map(Enum::name).toList();
        return attemptRepository.holdIfStillClaimed(attempt.getId(), names, claim.fence()) == 1;
    }

    /**
     * Only a notification; the DLQ state is already committed. The topic also carries the
     * container's poison records, so consumers must tolerate both shapes.
     */
    @Override
    public void onAbandoned(Claim claim) {
        try {
            kafkaTemplate.send(KafkaTopics.INCOMING_FORWARD_DLQ, claim.destinationId().toString(),
                    IncomingForwardMessage.builder()
                            .incomingEventId(claim.eventId())
                            .destinationId(claim.destinationId())
                            .incomingSourceId(event.getIncomingSourceId())
                            .attemptCount(claim.attemptNumber())
                            .replaySessionId(claim.replaySessionId())
                            .build());
            log.info("Published DLQ event for forward eventId={}, destId={}",
                    claim.eventId(), claim.destinationId());
        } catch (Exception e) {
            log.error("Failed to publish DLQ event for forward eventId={}, destId={}: {}",
                    claim.eventId(), claim.destinationId(), e.getMessage(), e);
        }
    }

    /** Incoming enforces no ordering, so nothing is released. */
    @Override
    public void onSucceeded(Claim claim) {
    }

    @Override
    public void recordTargetOutcome(Claim claim, boolean succeeded) {
        targetFailureRecorder.destinationAttempt(claim.destinationId(), succeeded);
    }

    private void applyRecord(IncomingForwardAttempt attempt) {
        if (pendingRecord == null) {
            return;
        }
        attempt.setRequestHeadersJson(pendingRecord.requestHeaders());
        attempt.setRequestBodySnippet(
                AttemptSupport.truncate(pendingRecord.requestBody(), REQUEST_BODY_SNIPPET_LIMIT));
        attempt.setResponseCode(pendingRecord.statusCode());
        attempt.setResponseHeadersJson(pendingRecord.responseHeaders());
        attempt.setResponseBodySnippet(AttemptSupport.truncate(pendingRecord.responseBody(), 10240));
        attempt.setErrorMessage(pendingRecord.errorMessage());
    }

    private IncomingForwardAttempt findAttempt(Claim claim) {
        List<IncomingForwardAttempt> attempts = attemptRepository.findForwardAttempts(
                claim.eventId(), claim.destinationId(), claim.replaySessionId());
        return attempts.stream()
                .filter(a -> a.getAttemptNumber() == claim.attemptNumber())
                .findFirst()
                .orElse(null);
    }

    private ForwardAttemptStatus statusFor(Finalization outcome) {
        if (outcome instanceof Finalization.Succeeded) {
            return ForwardAttemptStatus.SUCCESS;
        }
        if (outcome instanceof Finalization.Abandoned) {
            return ForwardAttemptStatus.DLQ;
        }
        if (outcome instanceof Finalization.Cancelled) {
            return ForwardAttemptStatus.CANCELLED;
        }
        return ForwardAttemptStatus.FAILED;
    }

    private String reasonFor(Finalization outcome) {
        if (outcome instanceof Finalization.Retry retry) {
            return retry.reason();
        }
        if (outcome instanceof Finalization.Abandoned abandoned) {
            return abandoned.reason();
        }
        if (outcome instanceof Finalization.TerminallyFailed failed) {
            return failed.reason();
        }
        return null;
    }

}
