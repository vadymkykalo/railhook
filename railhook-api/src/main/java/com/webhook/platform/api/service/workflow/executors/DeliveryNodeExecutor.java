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

/** Delivers the input as a new Event: an event id from input could name another organization's Event. */
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

    // Not around execute: its catch-all would commit a rollback-only transaction and throw on commit.
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

            entitlementService.checkEventQuota();

            String eventType = nodeConfig.hasNonNull("eventType") && !nodeConfig.get("eventType").asText().isBlank()
                    ? nodeConfig.get("eventType").asText() : DEFAULT_EVENT_TYPE;
            Event event = buildEvent(endpoint.getProjectId(), eventType, input);

            // The Delivery and its Outbox row commit together; this pool has no ambient transaction.
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

    // After the commit: the counter is not rolled back with a transaction.
    private void chargeQuota() {
        try {
            quotaCounterService.increment();
        } catch (Exception e) {
            log.error("Failed to charge quota for a committed workflow delivery event: {}", e.getMessage(), e);
        }
    }
}
