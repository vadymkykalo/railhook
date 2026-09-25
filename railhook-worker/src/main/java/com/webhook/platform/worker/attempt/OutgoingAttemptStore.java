package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.retry.RetryableStatuses;
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
import com.webhook.platform.common.transform.TransformRequest;
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
import org.springframework.http.MediaType;

/**
 * Outgoing mutates one {@code deliveries} row in place and appends a {@code delivery_attempts}
 * row per Attempt as a log. The FIFO ordering gate shows up at the seam only as
 * {@link ClaimResult.Deferred}.
 *
 * <p>One instance per Attempt; thread-confined.
 */
@Slf4j
public class OutgoingAttemptStore implements AttemptStore<OutgoingAttemptStore.Claim> {

    public record Claim(UUID deliveryId, UUID fence, Delivery delivery) {
    }

    /** The shared WebClient's default; repeated here only so the attempt record shows it. */
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
    private final TargetFailureRecorder targetFailureRecorder;
    private final Clock clock;

    private final DeliveryMessage message;
    private final boolean isRetry;

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
            TargetFailureRecorder targetFailureRecorder,
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
        this.targetFailureRecorder = targetFailureRecorder;
        this.clock = clock;
        this.message = message;
        this.isRetry = isRetry;
    }

    /** A retry CASes on the published token: the scheduler already moved the row to PROCESSING. */
    @Override
    public ClaimResult<Claim> claim() {
        Delivery delivery;
        UUID fence;

        if (isRetry) {
            UUID expected = message.getClaimToken();
            if (expected == null) {
                // Message from an older producer. Trust the status rather than strand the retry.
                delivery = deliveryRepository.findById(message.getDeliveryId()).orElse(null);
                if (delivery == null || delivery.getStatus() != Delivery.DeliveryStatus.PROCESSING) {
                    return new ClaimResult.NotClaimed<>("retry delivery not found or not PROCESSING");
                }
                log.debug("Retry message for delivery {} carries no fencing token (older producer?), "
                        + "proceeding without CAS", message.getDeliveryId());
                fence = delivery.getClaimToken();
            } else {
                // Reading the fence out of the row let every copy of a redelivered message
                // match, and the duplicate webhook went out unrecorded.
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

        // Before loading the Endpoint and Event: a parked Delivery is re-polled every few seconds.
        if (Boolean.TRUE.equals(delivery.getOrderingEnabled()) && delivery.getSequenceNumber() != null) {
            Instant until = orderingGate.holdUntil(delivery);
            if (until != null) {
                return new ClaimResult.Deferred<>(until, "waiting for an earlier sequence");
            }
        }

        // Checked after the claim so terminal failures are written under the fencing token.
        endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null) {
            return terminal(claim, "Endpoint not found");
        }
        if (endpoint.getDeletedAt() != null) {
            return terminal(claim, "Endpoint has been deleted");
        }
        // Project deletion and Organization suspension leave the Endpoint looking live. A
        // suspension is deferred because an operator can lift it.
        ProjectStatusLookup.ProjectStatus projectStatus = projectStatusLookup.forProject(endpoint.getProjectId());
        if (projectStatus == ProjectStatusLookup.ProjectStatus.DELETED) {
            return terminal(claim, "Project has been deleted");
        }
        if (projectStatus == ProjectStatusLookup.ProjectStatus.ORGANIZATION_SUSPENDED) {
            return deferred(claim, "Organization is suspended");
        }
        if (!endpoint.getEnabled()) {
            // Disabled by the owner: FAILED. Auto-disabled by us for continuous failure: DLQ, so a
            // person can fix the receiver and retry.
            if (endpoint.getAutoDisabledAt() != null) {
                return abandoned(claim, "Endpoint auto-disabled: "
                        + reasonOrDefault(endpoint.getAutoDisabledReason()));
            }
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
            String reason = "INVALID_RETRY_LADDER: " + e.getMessage();
            log.error("Delivery {} carries an unusable retry ladder: {}", delivery.getId(), e.getMessage());
            return terminal(claim, reason);
        }

        RetryableStatuses retryableStatuses;
        try {
            retryableStatuses = RetryableStatuses.parse(delivery.getRetryableStatuses());
        } catch (IllegalArgumentException e) {
            String reason = "INVALID_RETRYABLE_STATUSES: " + e.getMessage();
            log.error("Delivery {} carries an unusable retryable-status spec: {}",
                    delivery.getId(), e.getMessage());
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
                retryableStatuses,
                endpoint.getUrl(),
                AttemptSupport.clampTimeout(delivery.getTimeoutSeconds()));
        return new ClaimResult.Claimed<>(claim, context);
    }

    private ClaimResult<Claim> deferred(Claim claim, String reason) {
        Instant until = Instant.now().plus(ProjectStatusLookup.SUSPENSION_RECHECK);
        log.info("Delivery {} will not be attempted before {}: {}", claim.deliveryId(), until, reason);
        finalise(claim, new Finalization.Deferred(until, reason));
        return new ClaimResult.Deferred<>(until, reason);
    }

    /** Never reaches {@link AttemptRunner}, so it runs the side effect itself if finalise applied. */
    private ClaimResult<Claim> abandoned(Claim claim, String reason) {
        log.warn("Delivery {} will not be attempted and goes to Failed Messages: {}",
                claim.deliveryId(), reason);
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
        log.warn("Delivery {} will not be attempted: {}", claim.deliveryId(), reason);
        if (finalise(claim, new Finalization.TerminallyFailed(reason))) {
            onTerminallyFailed(claim);
        }
        return new ClaimResult.NotClaimed<>(reason);
    }

    @Override
    public RequestSpec buildRequest(Claim claim, TransformedBody transformed) {
        Delivery delivery = claim.delivery();
        DeliverySigner.Signatures signatures = new DeliverySigner(endpoint, encryptionKeyRegistry, clock)
                .sign(delivery.getId(), transformed.body());

        String sequenceHeader = delivery.getSequenceNumber() != null
                ? String.valueOf(delivery.getSequenceNumber())
                : "0";
        String idempotencyKey = delivery.getIdempotencyKey() != null
                ? delivery.getIdempotencyKey()
                : event.getId() + "-" + delivery.getEndpointId();

        WebClient client = Boolean.TRUE.equals(endpoint.getMtlsEnabled())
                ? mtlsWebClientFactory.getWebClient(endpoint)
                : defaultWebClient;

        // One set of headers feeds both the request and the record, which used to drift apart.
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
            sent.put("webhook-id", delivery.getId().toString());
            recorded.put("webhook-id", delivery.getId().toString());
            sent.put("webhook-timestamp", String.valueOf(signatures.timestampSeconds()));
            recorded.put("webhook-timestamp", String.valueOf(signatures.timestampSeconds()));
            sent.put("webhook-signature", signatures.standard());
            recorded.put("webhook-signature", signatures.maskedStandard());
        }
        recorded.put("User-Agent", USER_AGENT);

        Map<String, String> custom = new LinkedHashMap<>();
        // Script headers first so the Endpoint's custom headers win. Signatures are not in this
        // map, so a script cannot overwrite one.
        custom.putAll(transformed.headers());
        AttemptSupport.collectCustomHeaders(custom, delivery.getCustomHeaders(), objectMapper);
        recorded.putAll(HeaderSanitizer.sanitize(custom));

        return new RequestSpec(
                client,
                request -> {
                    request.contentType(MediaType.APPLICATION_JSON);
                    sent.forEach(request::header);
                    custom.forEach(request::header);
                },
                recordedHeaders(recorded));
    }

    /** A missing transformation fails the Attempt: falling back would ship what it strips. */
    @Override
    public TransformedBody buildBody(Claim claim) {
        Delivery delivery = claim.delivery();
        TransformationCacheService.Resolved resolved;
        if (delivery.getTransformationId() != null) {
            resolved = transformationCacheService.findEnabled(delivery.getTransformationId());
            if (resolved == null) {
                throw new PayloadTransformException(
                        "Configured transformation " + delivery.getTransformationId()
                                + " not found or disabled for delivery " + delivery.getId());
            }
        } else {
            resolved = TransformationCacheService.Resolved.template(delivery.getPayloadTemplate());
        }

        return payloadTransformService.apply(resolved, event.getDecompressedPayload(),
                TransformRequest.builder()
                        .payload(event.getDecompressedPayload())
                        .eventType(event.getEventType())
                        .eventId(event.getId().toString())
                        .timestamp(event.getCreatedAt())
                        .direction("OUTGOING")
                        .url(endpoint.getUrl())
                        // Signatures are added later and never shown: a script that can read a
                        // signature can leak one.
                        .headers(configuredHeaders(delivery.getCustomHeaders()))
                        // Already incremented by attemptStarting, so this is the current attempt.
                        .attemptNumber(delivery.getAttemptCount())
                        .build());
    }

    private Map<String, String> configuredHeaders(String customHeadersJson) {
        Map<String, String> configured = new LinkedHashMap<>();
        AttemptSupport.collectCustomHeaders(configured, customHeadersJson, objectMapper);
        return configured;
    }

    @Override
    public void attemptStarting(Claim claim) {
        Integer spent = transactionTemplate.execute(tx ->
                deliveryRepository.incrementAttemptCount(claim.deliveryId(), claim.fence()));
        if (spent == null || spent == 0) {
            log.warn("Delivery {} was reclaimed before its attempt started; the rung stays with the "
                    + "attempt that holds it now", claim.deliveryId());
        }
        claim.delivery().setAttemptCount(claim.delivery().getAttemptCount() + 1);
    }

    @Override
    public void recordAttempt(Claim claim, AttemptRecord record) {
        boolean success = record.statusCode() != null
                && record.statusCode() >= 200 && record.statusCode() < 300;
        deliveryAttemptRepository.save(DeliveryAttempt.builder()
                .deliveryId(claim.deliveryId())
                // The worker has no tenant scope to derive this from; the api filters on it.
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

    /** Checks the fence too: a swept and reclaimed row is PROCESSING again, for someone else. */
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
            } else if (outcome instanceof Finalization.Cancelled cancelled) {
                fresh.cancel();
                log.info("Delivery {} cancelled: {}", fresh.getId(), cancelled.reason());
            }
            deliveryRepository.save(fresh);
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    /** Only a notification; the DLQ state is already committed. */
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

    @Override
    public void recordTargetOutcome(Claim claim, boolean succeeded) {
        targetFailureRecorder.endpointAttempt(claim.delivery().getEndpointId(), succeeded);
    }

    /** The cursor must move past a failed Delivery too, or the ordered endpoint stalls forever. */
    @Override
    public void onTerminallyFailed(Claim claim) {
        orderingGate.release(claim.delivery(), true);
    }

    private boolean stillHoldsClaim(Delivery fresh, Claim claim) {
        return AttemptSupport.fenceMatches(fresh.getClaimToken(), claim.fence());
    }

    private String recordedHeaders(Map<String, String> recorded) {
        try {
            return objectMapper.writeValueAsString(recorded);
        } catch (Exception e) {
            log.warn("Failed to serialise delivery request headers: {}", e.getMessage());
            return null;
        }
    }

}
