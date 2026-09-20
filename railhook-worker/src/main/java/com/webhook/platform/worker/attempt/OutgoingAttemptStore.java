package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.HeaderSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.DeliveryAttempt;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.entity.Event;
import com.webhook.platform.worker.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.EventRepository;
import com.webhook.platform.worker.service.MtlsWebClientFactory;
import com.webhook.platform.worker.service.OrderingBufferService;
import com.webhook.platform.worker.service.PayloadTransformException;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.TransformationCacheService;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How the Outgoing direction records its Attempts: one {@code deliveries} row mutated in
 * place, with a separate {@code delivery_attempts} row appended per Attempt as a log.
 *
 * <p>The fence and the FIFO ordering gate live here and never reach {@link AttemptRunner};
 * the gate appears at the seam as {@link ClaimResult.Deferred}, because parking a Delivery
 * already means the Claim was released and nothing was sent.
 *
 * <p>One instance per Attempt; thread-confined.
 */
@Slf4j
public class OutgoingAttemptStore implements AttemptStore<OutgoingAttemptStore.Claim> {

    /** Ownership of one delivery row; {@code fence} is the token stamped when it was taken. */
    public record Claim(UUID deliveryId, UUID fence, Delivery delivery) {
    }

    /** Set as a default on the shared WebClient (WebClientConfig); recorded here, since it is sent. */
    private static final String USER_AGENT = "WebhookPlatform/1.0";

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final EndpointRepository endpointRepository;
    private final EventRepository eventRepository;
    private final ProjectStatusLookup projectStatusLookup;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    private final OrderingGate orderingGate;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final MtlsWebClientFactory mtlsWebClientFactory;
    private final TransformationCacheService transformationCacheService;
    private final PayloadTransformService payloadTransformService;
    private final ObjectMapper objectMapper;
    private final WebClient defaultWebClient;
    private final Clock clock;

    private final DeliveryMessage message;
    private final boolean isRetry;

    // Resolved inside claim(), after the ordering gate — see the note there.
    private Endpoint endpoint;
    private Event event;

    public OutgoingAttemptStore(
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            EndpointRepository endpointRepository,
            EventRepository eventRepository,
            ProjectStatusLookup projectStatusLookup,
            TransactionTemplate transactionTemplate,
            OrderingBufferService orderingBufferService,
            KafkaTemplate<String, DeliveryMessage> kafkaTemplate,
            EncryptionKeyRegistry encryptionKeyRegistry,
            MtlsWebClientFactory mtlsWebClientFactory,
            TransformationCacheService transformationCacheService,
            PayloadTransformService payloadTransformService,
            ObjectMapper objectMapper,
            WebClient defaultWebClient,
            Counter orderingGapTimeoutCounter,
            Clock clock,
            int orderingRescheduleDelaySeconds,
            DeliveryMessage message,
            boolean isRetry) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.projectStatusLookup = projectStatusLookup;
        this.transactionTemplate = transactionTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.orderingGate = new OrderingGate(orderingBufferService, deliveryRepository, kafkaTemplate,
                transactionTemplate, orderingGapTimeoutCounter, orderingRescheduleDelaySeconds);
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.mtlsWebClientFactory = mtlsWebClientFactory;
        this.transformationCacheService = transformationCacheService;
        this.payloadTransformService = payloadTransformService;
        this.objectMapper = objectMapper;
        this.defaultWebClient = defaultWebClient;
        this.clock = clock;
        this.message = message;
        this.isRetry = isRetry;
    }

    /**
     * Claim, then apply the ordering gate.
     *
     * <p>The retry path does not re-claim: RetrySchedulerService already moved the row to
     * PROCESSING before publishing, so an {@code UPDATE … WHERE status = 'PENDING'} would
     * never match and every retry would be silently skipped.
     */
    @Override
    public ClaimResult<Claim> claim() {
        Delivery delivery;
        UUID fence;

        if (isRetry) {
            UUID expected = message.getClaimToken();
            if (expected == null) {
                // Published before the token travelled with the message. Trust the status
                // rather than strand every retry already in flight.
                delivery = deliveryRepository.findById(message.getDeliveryId()).orElse(null);
                if (delivery == null || delivery.getStatus() != Delivery.DeliveryStatus.PROCESSING) {
                    return new ClaimResult.NotClaimed<>("retry delivery not found or not PROCESSING");
                }
                log.debug("Retry message for delivery {} carries no fencing token (older producer?), "
                        + "proceeding without CAS", message.getDeliveryId());
                fence = delivery.getClaimToken();
            } else {
                // CAS on the token the scheduler published with, not on the status: reading
                // the fence out of the row let every copy of a redelivered message match, and
                // the second webhook went out with nothing recording it.
                UUID token = UUID.randomUUID();
                delivery = transactionTemplate.execute(tx ->
                        deliveryRepository.claimRetryForProcessing(message.getDeliveryId(), expected, token));
                if (delivery == null) {
                    return new ClaimResult.NotClaimed<>(
                            "retry delivery already claimed by a prior delivery of this Kafka message");
                }
                fence = token;
            }
        } else {
            UUID token = UUID.randomUUID();
            delivery = transactionTemplate.execute(tx ->
                    deliveryRepository.claimForProcessingAndReturn(message.getDeliveryId(), token, clock.instant()));
            if (delivery == null) {
                return new ClaimResult.NotClaimed<>("delivery already claimed or not PENDING");
            }
            fence = token;
        }

        Claim claim = new Claim(delivery.getId(), fence, delivery);

        // Before the Endpoint and Event are read: a parked Delivery is re-polled every few
        // seconds, and loading both rows each time put two reads per poll on the hot path.
        if (Boolean.TRUE.equals(delivery.getOrderingEnabled()) && delivery.getSequenceNumber() != null) {
            Instant until = orderingGate.holdUntil(delivery);
            if (until != null) {
                return new ClaimResult.Deferred<>(until, "waiting for an earlier sequence");
            }
        }

        // Resolved after the claim rather than before it, so these terminal failures are
        // written under the fencing token like every other finalisation.
        endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null) {
            return terminal(claim, "Endpoint not found");
        }
        // A soft delete is a deletion as far as the owner is concerned: stop delivering,
        // including for events already queued or partway through the retry ladder.
        if (endpoint.getDeletedAt() != null) {
            return terminal(claim, "Endpoint has been deleted");
        }
        // Deleting a Project or suspending an Organization stamps that row and nothing under it,
        // so the Endpoint reads as live here. A deleted Project ends as a deleted Endpoint does;
        // a suspension is handed back, because an operator can lift it.
        ProjectStatusLookup.ProjectStatus projectStatus = projectStatusLookup.forProject(endpoint.getProjectId());
        if (projectStatus == ProjectStatusLookup.ProjectStatus.DELETED) {
            return terminal(claim, "Project has been deleted");
        }
        if (projectStatus == ProjectStatusLookup.ProjectStatus.ORGANIZATION_SUSPENDED) {
            return deferred(claim, "Organization is suspended");
        }
        if (!endpoint.getEnabled()) {
            return terminal(claim, "Endpoint is disabled");
        }
        if (endpoint.getVerificationStatus() != Endpoint.VerificationStatus.VERIFIED
                && endpoint.getVerificationStatus() != Endpoint.VerificationStatus.SKIPPED) {
            return terminal(claim,
                    "Endpoint not verified - verification required before receiving webhooks");
        }

        event = eventRepository.findById(delivery.getEventId()).orElse(null);
        if (event == null) {
            return terminal(claim, "Event not found");
        }

        RetryLadder ladder;
        try {
            ladder = RetryLadder.parse(delivery.getRetryDelays(), delivery.getMaxAttempts());
        } catch (IllegalArgumentException e) {
            // No number of retries fixes a ladder that does not parse.
            String reason = "INVALID_RETRY_LADDER: " + e.getMessage();
            log.error("Delivery {} carries an unusable retry ladder: {}", delivery.getId(), e.getMessage());
            return terminal(claim, reason);
        }

        AttemptContext context = new AttemptContext(
                "delivery " + delivery.getId() + " attempt " + (delivery.getAttemptCount() + 1)
                        + "/" + delivery.getMaxAttempts(),
                endpoint.getProjectId(),
                endpoint.getId(),
                endpoint.getRateLimitPerSecond(),
                delivery.getAttemptCount() + 1,
                ladder,
                endpoint.getUrl(),
                AttemptSupport.clampTimeout(delivery.getTimeoutSeconds()));
        return new ClaimResult.Claimed<>(claim, context);
    }

    /**
     * Hands the Delivery back under its fencing token, unattempted, until {@link
     * ProjectStatusLookup#SUSPENSION_RECHECK} from now.
     */
    private ClaimResult<Claim> deferred(Claim claim, String reason) {
        Instant until = Instant.now().plus(ProjectStatusLookup.SUSPENSION_RECHECK);
        log.info("Delivery {} will not be attempted before {}: {}", claim.deliveryId(), until, reason);
        finalise(claim, new Finalization.Deferred(until, reason));
        return new ClaimResult.Deferred<>(until, reason);
    }

    /** Fails the Delivery under its fencing token and reports that there is nothing to attempt. */
    private ClaimResult<Claim> terminal(Claim claim, String reason) {
        log.warn("Delivery {} will not be attempted: {}", claim.deliveryId(), reason);
        // Never reaches AttemptRunner, so it owes the release itself — and only if its own
        // finalisation applied.
        if (finalise(claim, new Finalization.TerminallyFailed(reason))) {
            onTerminallyFailed(claim);
        }
        return new ClaimResult.NotClaimed<>(reason);
    }

    @Override
    public RequestSpec buildRequest(Claim claim, String body) {
        Delivery delivery = claim.delivery();
        DeliverySigner.Signatures signatures =
                new DeliverySigner(endpoint, encryptionKeyRegistry, clock).sign(delivery.getId(), body);

        String sequenceHeader = delivery.getSequenceNumber() != null
                ? String.valueOf(delivery.getSequenceNumber())
                : "0";
        String idempotencyKey = delivery.getIdempotencyKey() != null
                ? delivery.getIdempotencyKey()
                : event.getId() + "-" + delivery.getEndpointId();

        WebClient client = Boolean.TRUE.equals(endpoint.getMtlsEnabled())
                ? mtlsWebClientFactory.getWebClient(endpoint)
                : defaultWebClient;

        // One set of headers feeds both the request and the Attempt's record. They used to be
        // written out twice, and the record lost X-Sequence-Number and Idempotency-Key while the
        // request kept them.
        Map<String, String> sent = new LinkedHashMap<>();
        Map<String, String> recorded = new LinkedHashMap<>();
        recorded.put("Content-Type", "application/json");
        sent.put("X-Event-Id", event.getId().toString());
        sent.put("X-Delivery-Id", delivery.getId().toString());
        sent.put("X-Sequence-Number", sequenceHeader);
        sent.put("Idempotency-Key", idempotencyKey);
        recorded.putAll(sent);
        if (signatures.legacy() != null) {
            sent.put("X-Signature", signatures.legacy());
            recorded.put("X-Signature", signatures.maskedLegacy());
            sent.put("X-Timestamp", String.valueOf(signatures.timestampMillis()));
            recorded.put("X-Timestamp", String.valueOf(signatures.timestampMillis()));
        }
        if (signatures.standard() != null) {
            // Lower-case as the convention spells them; cosmetic on the wire.
            sent.put("webhook-id", delivery.getId().toString());
            recorded.put("webhook-id", delivery.getId().toString());
            sent.put("webhook-timestamp", String.valueOf(signatures.timestampSeconds()));
            recorded.put("webhook-timestamp", String.valueOf(signatures.timestampSeconds()));
            sent.put("webhook-signature", signatures.standard());
            recorded.put("webhook-signature", signatures.maskedStandard());
        }
        // The WebClient's default, which a custom header of the same name replaces.
        recorded.put("User-Agent", USER_AGENT);

        Map<String, String> custom = new LinkedHashMap<>();
        AttemptSupport.collectCustomHeaders(custom, delivery.getCustomHeaders(), objectMapper);
        recorded.putAll(HeaderSanitizer.sanitize(custom));

        return new RequestSpec(
                client,
                request -> {
                    request.contentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    sent.forEach(request::header);
                    custom.forEach(request::header);
                },
                recordedHeaders(recorded));
    }

    /**
     * A missing or disabled {@code transformationId} is a configuration failure, not "no
     * transform": falling back would ship the data the transform exists to strip.
     */
    @Override
    public String buildBody(Claim claim) {
        Delivery delivery = claim.delivery();
        String template;
        if (delivery.getTransformationId() != null) {
            template = transformationCacheService.findEnabledTemplate(delivery.getTransformationId());
            if (template == null) {
                throw new PayloadTransformException(
                        "Configured transformation " + delivery.getTransformationId()
                                + " not found or disabled for delivery " + delivery.getId());
            }
        } else {
            template = delivery.getPayloadTemplate();
        }
        return payloadTransformService.transform(event.getDecompressedPayload(), template);
    }

    @Override
    public void attemptStarting(Claim claim) {
        Integer spent = transactionTemplate.execute(tx ->
                deliveryRepository.incrementAttemptCount(claim.deliveryId(), claim.fence()));
        if (spent == null || spent == 0) {
            // Its finalisation will not apply either, so it queues nothing.
            log.warn("Delivery {} was reclaimed before its attempt started; the rung stays with the "
                    + "attempt that holds it now", claim.deliveryId());
        }
        claim.delivery().setAttemptCount(claim.delivery().getAttemptCount() + 1);
    }

    /** Outgoing keeps a separate row per Attempt, with more of the body kept for failures. */
    @Override
    public void recordAttempt(Claim claim, AttemptRecord record) {
        boolean success = record.statusCode() != null
                && record.statusCode() >= 200 && record.statusCode() < 300;
        deliveryAttemptRepository.save(DeliveryAttempt.builder()
                .deliveryId(claim.deliveryId())
                // Carried across from the Delivery: the api filters delivery_attempts on it and
                // the worker has no tenant of its own to derive it from.
                .organizationId(claim.delivery().getOrganizationId())
                .attemptNumber(claim.delivery().getAttemptCount())
                .requestHeaders(record.requestHeaders())
                .requestBody(AttemptSupport.truncate(record.requestBody(), 10240))
                .httpStatusCode(record.statusCode())
                .responseHeaders(record.responseHeaders())
                .responseBody(AttemptSupport.truncate(record.responseBody(), success ? 2048 : 10240))
                .errorMessage(record.errorMessage())
                .durationMs(record.durationMs())
                .build());
    }

    /**
     * Re-reads the row and writes only while this Attempt still holds the Claim.
     *
     * <p>The status alone is not enough: a swept row reclaimed by another Attempt is PROCESSING
     * again, for somebody else. Both tokens null is a match, so a row claimed before the token
     * existed is not stranded; a mismatch is not.
     */
    @Override
    public boolean finalise(Claim claim, Finalization outcome) {
        Boolean applied = transactionTemplate.execute(tx -> {
            Delivery fresh = deliveryRepository.findById(claim.deliveryId()).orElse(null);
            if (fresh == null) {
                log.warn("Delivery {} disappeared during finalisation", claim.deliveryId());
                return false;
            }
            if (fresh.getStatus() != Delivery.DeliveryStatus.PROCESSING) {
                log.debug("Delivery {} no longer PROCESSING (status={}), skipping finalisation",
                        fresh.getId(), fresh.getStatus());
                return false;
            }
            if (!stillHoldsClaim(fresh, claim)) {
                log.warn("Delivery {} was reclaimed by another attempt, skipping finalisation", fresh.getId());
                return false;
            }

            if (outcome instanceof Finalization.Succeeded) {
                fresh.succeed();
            } else if (outcome instanceof Finalization.Deferred deferred) {
                fresh.handBackTo(deferred.until());
            } else if (outcome instanceof Finalization.Retry retry) {
                fresh.handBackTo(retry.at());
            } else if (outcome instanceof Finalization.Abandoned) {
                fresh.abandon();
            } else if (outcome instanceof Finalization.TerminallyFailed failed) {
                fresh.failTerminally();
                log.error("Delivery {} failed: {}", fresh.getId(), failed.reason());
            }
            deliveryRepository.save(fresh);
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    /** Outside the finalising transaction: the DLQ write is committed, this is a notification. */
    @Override
    public void onAbandoned(Claim claim) {
        Delivery delivery = claim.delivery();
        orderingGate.release(delivery, true);
        try {
            kafkaTemplate.send(KafkaTopics.DELIVERIES_DLQ, delivery.getEndpointId().toString(),
                    DeliveryMessage.builder()
                            .deliveryId(delivery.getId())
                            .eventId(delivery.getEventId())
                            .endpointId(delivery.getEndpointId())
                            .subscriptionId(delivery.getSubscriptionId())
                            .status(Delivery.DeliveryStatus.DLQ.name())
                            .attemptCount(delivery.getAttemptCount())
                            .sequenceNumber(delivery.getSequenceNumber())
                            .orderingEnabled(delivery.getOrderingEnabled())
                            .build());
            log.info("Published DLQ event for delivery {}", delivery.getId());
        } catch (Exception e) {
            log.error("Failed to publish DLQ event for delivery {}: {}", delivery.getId(), e.getMessage(), e);
        }
    }

    @Override
    public void onSucceeded(Claim claim) {
        orderingGate.release(claim.delivery(), false);
    }

    /**
     * The cursor has to move past a terminally failed Delivery too, or a single non-retryable
     * 4xx parks an ordering-enabled endpoint at that sequence forever. Removed from the buffer
     * as well: nothing is coming along behind it to clean up.
     */
    @Override
    public void onTerminallyFailed(Claim claim) {
        orderingGate.release(claim.delivery(), true);
    }

    private boolean stillHoldsClaim(Delivery fresh, Claim claim) {
        return AttemptSupport.fenceMatches(fresh.getClaimToken(), claim.fence());
    }

    /** What the dashboard shows for this request; signatures and custom secrets already masked. */
    private String recordedHeaders(Map<String, String> recorded) {
        try {
            return objectMapper.writeValueAsString(recorded);
        } catch (Exception e) {
            log.warn("Failed to serialise delivery request headers: {}", e.getMessage());
            return null;
        }
    }

}
