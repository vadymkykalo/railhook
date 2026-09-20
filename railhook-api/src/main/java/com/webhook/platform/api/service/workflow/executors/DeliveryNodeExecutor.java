package com.webhook.platform.api.service.workflow.executors;

import com.webhook.platform.common.retry.RetryableStatuses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.service.DeliveryDispatch;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.service.billing.QuotaCounterService;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.util.PayloadCompressionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Delivers the node's input to an existing platform endpoint using the standard
 * Delivery → Outbox → Kafka pipeline.
 *
 * <p>A Delivery points at an Event, and the worker sends that Event's payload. The node records its
 * own input as an Event in the endpoint's project and delivers that. It used to take the Event from
 * {@code _eventId} in its input: nothing on the server set that, so every delivery node failed, and
 * a customer who set it could point the Delivery at any organization's Event — the worker loads
 * Events without a tenant filter. The endpoint lookup is tenant-scoped, so the project, and the
 * Event with it, are always the workflow's own organization.
 *
 * <p>Config: {@code endpointId} (required), {@code eventType} (optional, default
 * {@value #DEFAULT_EVENT_TYPE}).
 */
@Component
@Slf4j
public class DeliveryNodeExecutor implements NodeExecutor {

    static final String DEFAULT_EVENT_TYPE = "workflow.delivery";

    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;
    private final EventRepository eventRepository;
    private final EntitlementService entitlementService;
    private final QuotaCounterService quotaCounterService;
    private final ObjectMapper objectMapper;
    private final DeliveryDispatch deliveryDispatch;
    private final long maxPayloadSizeBytes;
    private final int compressionThresholdBytes;

    /**
     * Scoped to the writes, not to {@link #execute}.
     *
     * <p>{@code execute} ends in a catch-all that turns any failure into a failed StepResult. A
     * {@code @Transactional} around all of it would therefore return normally from a transaction
     * an inner failure had already marked rollback-only, and the commit would throw
     * UnexpectedRollbackException from somewhere with no bearing on the cause.
     */
    private final TransactionTemplate txTemplate;

    public DeliveryNodeExecutor(EndpointRepository endpointRepository,
            DeliveryRepository deliveryRepository,
            EventRepository eventRepository,
            EntitlementService entitlementService,
            QuotaCounterService quotaCounterService,
            ObjectMapper objectMapper,
            DeliveryDispatch deliveryDispatch,
            PlatformTransactionManager transactionManager,
            @Value("${webhook.max-payload-size-bytes:262144}") long maxPayloadSizeBytes,
            @Value("${webhook.payload-compression-threshold-bytes:1024}") int compressionThresholdBytes) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.entitlementService = entitlementService;
        this.quotaCounterService = quotaCounterService;
        this.objectMapper = objectMapper;
        this.deliveryDispatch = deliveryDispatch;
        this.maxPayloadSizeBytes = maxPayloadSizeBytes;
        this.compressionThresholdBytes = compressionThresholdBytes;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public String getType() {
        return "delivery";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        try {
            String endpointIdStr = nodeConfig.has("endpointId") ? nodeConfig.get("endpointId").asText() : null;
            if (endpointIdStr == null || endpointIdStr.isBlank()) {
                return StepResult.failed("Delivery node: endpointId is required");
            }

            UUID endpointId;
            try {
                endpointId = UUID.fromString(endpointIdStr);
            } catch (IllegalArgumentException e) {
                return StepResult.failed("Delivery node: invalid endpointId format");
            }

            Endpoint endpoint = endpointRepository.findById(endpointId).orElse(null);
            if (endpoint == null || endpoint.getDeletedAt() != null) {
                return StepResult.failed("Delivery node: endpoint not found or deleted");
            }
            if (!Boolean.TRUE.equals(endpoint.getEnabled())) {
                return StepResult.skipped("Delivery node: endpoint is disabled");
            }

            // The Event recorded below is charged like any other, so it is checked like one.
            entitlementService.checkEventQuota();

            String eventType = nodeConfig.hasNonNull("eventType") && !nodeConfig.get("eventType").asText().isBlank()
                    ? nodeConfig.get("eventType").asText() : DEFAULT_EVENT_TYPE;
            Event event = buildEvent(endpoint.getProjectId(), eventType, input);

            // One transaction, which is what DeliveryDispatch's contract asks of every caller:
            // the Outbox row is written in the same breath as the Delivery, so the two cannot
            // disagree about whether the work exists. This node had neither an annotation nor a
            // template, and WorkflowEngine runs it on its own pool so there was no ambient
            // transaction to inherit — two auto-commits, and a window between them that left a
            // PENDING Delivery with no Outbox row and next_retry_at NULL. Nothing dispatches
            // that; it waits an hour for the stranded-PENDING sweep.
            Delivery delivery = txTemplate.execute(tx -> {
                Event saved = eventRepository.saveAndFlush(event);
                Delivery created = deliveryRepository.save(Delivery.builder()
                        .eventId(saved.getId())
                        .endpointId(endpointId)
                        .status(DeliveryStatus.PENDING)
                        .attemptCount(0)
                        .maxAttempts(7)
                        .orderingEnabled(false)
                        .timeoutSeconds(30)
                        .retryDelays(RetryLadderDefaults.OUTGOING_DELAYS)
                        .retryableStatuses(RetryableStatuses.DEFAULT_SPEC)
                        .build());
                deliveryDispatch.announce(created, endpoint.getProjectId(),
                        DeliveryDispatch.Reason.WORKFLOW_CREATED);
                return created;
            });
            chargeQuota();

            log.info("Workflow delivery created: {} → endpoint {} (event {})",
                    delivery.getId(), endpointId, delivery.getEventId());

            ObjectNode result = objectMapper.createObjectNode();
            result.put("deliveryId", delivery.getId().toString());
            result.put("eventId", delivery.getEventId().toString());
            result.put("endpointId", endpointId.toString());
            result.put("endpointUrl", endpoint.getUrl());
            result.put("status", "PENDING");
            return StepResult.success(result);
        } catch (Exception e) {
            log.error("Delivery node execution failed: {}", e.getMessage(), e);
            return StepResult.failed("Delivery error: " + e.getMessage());
        }
    }

    private Event buildEvent(UUID projectId, String eventType, JsonNode input) throws Exception {
        String payload = objectMapper.writeValueAsString(input != null ? input : objectMapper.createObjectNode());
        long payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > maxPayloadSizeBytes) {
            throw new IllegalArgumentException("Event payload size (" + payloadBytes
                    + " bytes) exceeds maximum allowed size (" + maxPayloadSizeBytes + " bytes)");
        }
        PayloadCompressionUtil.CompressionResult compression =
                PayloadCompressionUtil.compress(payload, compressionThresholdBytes);
        return Event.builder()
                .projectId(projectId)
                .eventType(eventType)
                .payload(compression.payload())
                .payloadCompressed(compression.compressed())
                .build();
    }

    /** After the commit, as the ingest path does: the counter is not rolled back with a transaction. */
    private void chargeQuota() {
        try {
            quotaCounterService.increment();
        } catch (Exception e) {
            log.error("Failed to charge quota for a committed workflow delivery event: {}", e.getMessage(), e);
        }
    }
}
