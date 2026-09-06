package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.HeaderSanitizer;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.worker.service.PayloadTransformException;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.TransformationCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How the Incoming direction records its Attempts: one
 * {@code incoming_forward_attempts} row per Attempt, with the successor inserted when the
 * current one is finalised as retryable.
 *
 * <p>{@link OutgoingAttemptStore} mutates a single row in place instead. Neither model can
 * move to the other's — both are public through DTOs, dashboard pages and usage aggregation —
 * which is why this seam has two adapters.
 *
 * <p>One instance per Attempt; thread-confined.
 */
@Slf4j
public class IncomingAttemptStore implements AttemptStore<IncomingAttemptStore.Claim> {

    /** What {@code delivery_attempts} keeps of a request body; Incoming keeps the same. */
    private static final int REQUEST_BODY_SNIPPET_LIMIT = 10240;

    /**
     * Ownership of one forward attempt row.
     *
     * @param fence           the claim_token this attempt was claimed under, null only for a
     *                        retry message published before the token existed
     * @param replaySessionId the Replay this Forward belongs to, null for one created by
     *                        ingress. Part of the Claim because it is part of the row's
     *                        identity: (event, destination, attempt number) alone names two
     *                        different rows once a Replay has started a second ladder.
     */
    public record Claim(UUID eventId, UUID destinationId, int attemptNumber, UUID fence,
            UUID replaySessionId) {
    }

    private final IncomingForwardAttemptRepository attemptRepository;
    private final TransactionTemplate transactionTemplate;
    private final TransformationCacheService transformationCacheService;
    private final PayloadTransformService payloadTransformService;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate;

    private final IncomingForwardMessage message;
    private final IncomingEvent event;
    private final IncomingDestination destination;

    public IncomingAttemptStore(
            IncomingForwardAttemptRepository attemptRepository,
            TransactionTemplate transactionTemplate,
            TransformationCacheService transformationCacheService,
            PayloadTransformService payloadTransformService,
            EncryptionKeyRegistry encryptionKeyRegistry,
            ObjectMapper objectMapper,
            WebClient webClient,
            KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate,
            IncomingForwardMessage message,
            IncomingEvent event,
            IncomingDestination destination) {
        this.attemptRepository = attemptRepository;
        this.transactionTemplate = transactionTemplate;
        this.transformationCacheService = transformationCacheService;
        this.payloadTransformService = payloadTransformService;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.objectMapper = objectMapper;
        this.webClient = webClient;
        this.kafkaTemplate = kafkaTemplate;
        this.message = message;
        this.event = event;
        this.destination = destination;
    }

    /**
     * Three entry paths, each claiming a row that already exists: first dispatch and replay
     * claim a PENDING row outright; retry CASes on the token the scheduler stamped, because a
     * redelivered Kafka message would otherwise see PROCESSING and double-POST.
     *
     * <p>Never returns {@link ClaimResult.Deferred}: FIFO ordering is an Outgoing concern.
     */
    @Override
    public ClaimResult<Claim> claim() {
        boolean isRetry = message.getAttemptCount() != null && message.getAttemptCount() > 0;
        boolean isReplay = message.isReplay();

        int attemptNumber = isRetry ? message.getAttemptCount() : 1;

        // Generated here, not in SQL, so the winner knows what it must still match.
        UUID claimToken = UUID.randomUUID();

        if (isRetry && !isReplay) {
            Instant expected = message.getStartedAt();
            if (expected == null) {
                // Published before the fencing token existed; dropping every in-flight retry
                // would be worse.
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
            // The token this CAS just stamped, not the started_at it matched on: the latter
            // had already been superseded, making it no fence at all.
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
     * Admissibility, once the Claim is held. Deliberately after it rather than before: a
     * Destination that has been turned off is turned off for Forwards already queued or partway
     * through the Ladder too, and failing one of those has to be written under the fencing token
     * like every other finalisation. The enabled check used to run in IncomingForwardService
     * before anything was claimed, so it wrote FAILED over whatever else owned the row.
     */
    private ClaimResult<Claim> claimed(int attemptNumber, UUID fence) {
        Claim claim = new Claim(event.getId(), destination.getId(), attemptNumber, fence,
                message.getReplaySessionId());

        if (!Boolean.TRUE.equals(destination.getEnabled())) {
            return terminal(claim, "Destination is disabled");
        }

        RetryLadder ladder;
        try {
            ladder = RetryLadder.parse(destination.getRetryDelays(), destination.getMaxAttempts());
        } catch (IllegalArgumentException e) {
            // Retrying cannot fix a ladder that does not parse. The api rejects a malformed
            // one on write, so reaching this means the column was written outside the api.
            log.error("Destination {} carries an unusable retry ladder: {}", destination.getId(), e.getMessage());
            return terminal(claim, "INVALID_RETRY_LADDER: " + e.getMessage());
        }

        AttemptContext context = new AttemptContext(
                "forward eventId=" + event.getId() + " destId=" + destination.getId()
                        + " attempt=" + attemptNumber + "/" + destination.getMaxAttempts(),
                event.getIncomingSourceId(),
                destination.getId(),
                null, // Destinations carry no per-target rate limit of their own
                attemptNumber,
                ladder,
                destination.getUrl(),
                AttemptSupport.clampTimeout(destination.getTimeoutSeconds()));
        return new ClaimResult.Claimed<>(claim, context);
    }

    /** Fails the Forward under its fencing token and reports that there is nothing to attempt. */
    private ClaimResult<Claim> terminal(Claim claim, String reason) {
        log.warn("Forward eventId={}, destId={} will not be attempted: {}",
                claim.eventId(), claim.destinationId(), reason);
        // Never reaches AttemptRunner, so it owes the release itself — and only if its own
        // finalisation applied.
        if (finalise(claim, new Finalization.TerminallyFailed(reason))) {
            onTerminallyFailed(claim);
        }
        return new ClaimResult.NotClaimed<>(reason);
    }

    @Override
    public RequestSpec buildRequest(Claim claim, String body) {
        String contentType = event.getContentType() != null ? event.getContentType() : "application/json";
        String idempotencyKey = event.getId() + "-" + destination.getId();

        // Collected before they are applied, because the attempt row records what went out and
        // a header handed to the request builder cannot be read back off it.
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("X-Incoming-Event-Id", event.getId().toString());
        if (event.getRequestId() != null) {
            headers.put("X-Incoming-Request-Id", event.getRequestId());
        }
        headers.put("X-Forward-Attempt", String.valueOf(claim.attemptNumber()));
        headers.put("Idempotency-Key", idempotencyKey);
        new DestinationAuthenticator(destination, encryptionKeyRegistry, objectMapper).authenticate(headers);
        AttemptSupport.collectCustomHeaders(headers, destination.getCustomHeadersJson(), objectMapper);

        return new RequestSpec(webClient, request -> headers.forEach(request::header), recorded(headers));
    }

    /**
     * What the dashboard shows for this request. The Destination's own credentials go out on
     * every Forward, so they are masked here rather than anywhere downstream.
     */
    private String recorded(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(HeaderSanitizer.sanitize(headers));
        } catch (Exception e) {
            log.warn("Failed to serialise forward request headers: {}", e.getMessage());
            return null;
        }
    }

    /**
     * A reusable Transformation by id, then an inline JSONPath expression, then the body
     * unchanged. Nothing configured forwards as-is; something configured that fails to apply
     * fails the Attempt, because transformations are how PII is stripped before relaying.
     */
    @Override
    public String buildBody(Claim claim) {
        String body = event.getBodyRaw();
        if (body == null || body.isBlank()) {
            return body;
        }

        if (destination.getTransformationId() != null) {
            String template = transformationCacheService.findEnabledTemplate(destination.getTransformationId());
            if (template == null) {
                throw new PayloadTransformException(
                        "Configured transformation " + destination.getTransformationId()
                                + " not found or disabled for destination " + destination.getId());
            }
            return payloadTransformService.transform(body, template);
        }

        String inline = destination.getPayloadTransform();
        if (inline == null || inline.isBlank()) {
            return body;
        }
        try {
            Object result = JsonPath.read(body, inline);
            if (result instanceof String s) {
                return s;
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new PayloadTransformException(
                    "Inline payload transform failed for destination " + destination.getId() + ": " + e.getMessage(), e);
        }
    }

    /** The attempt row already carries its own number; nothing to consume. */
    @Override
    public void attemptStarting(Claim claim) {
    }

    /** Held until {@link #finalise}, which writes the response fields onto the row itself. */
    @Override
    public void recordAttempt(Claim claim, AttemptRecord record) {
        this.pendingRecord = record;
    }

    private AttemptRecord pendingRecord;

    /**
     * Writes the outcome onto the PROCESSING row, and only while it is still PROCESSING: a late
     * writer used to overwrite a terminal row and queue a duplicate forward. Whoever got here
     * first won.
     */
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
                // Nothing was sent, so no attempt is consumed. next_retry_at must be set: the
                // scheduler ignores rows without one.
                if (attempt.getStatus() != ForwardAttemptStatus.PENDING
                        && attempt.getStatus() != ForwardAttemptStatus.PROCESSING) {
                    return false;
                }
                if (!stillHoldsClaim(claim, attempt)) {
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

            if (!stillHoldsClaim(claim, attempt)) {
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

            // Only the Attempt that actually finalised may queue a successor.
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

    private boolean stillHoldsClaim(Claim claim, IncomingForwardAttempt attempt) {
        return AttemptSupport.fenceMatches(attempt.getClaimToken(), claim.fence());
    }

    /**
     * Publishes the DLQ notification for a Forward whose Retry Ladder is exhausted.
     *
     * <p>Outside the finalising transaction: the DLQ write is committed and this is only a
     * notification. The topic also carries the container's poison records, so anything consuming
     * it must tolerate both shapes; the actionable count is the DB-backed gauge.
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

    /** Incoming enforces no ordering, so nothing has to be released on success. */
    @Override
    public void onSucceeded(Claim claim) {
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
        return ForwardAttemptStatus.FAILED;
    }

    private String reasonFor(Finalization outcome) {
        if (outcome instanceof Finalization.Retry retry) {
            return retry.reason();
        }
        if (outcome instanceof Finalization.Abandoned abandoned) {
            return "Max attempts reached: " + abandoned.reason();
        }
        if (outcome instanceof Finalization.TerminallyFailed failed) {
            return failed.reason();
        }
        return null;
    }

}
