package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Announces a Delivery to the worker, by writing the Outbox row in the same transaction as the
 * Delivery itself.
 *
 * <p>Every path that creates work for the worker goes through here — ingest, replay, bulk replay,
 * DLQ retry, a workflow node. Written out at each of those call sites instead, the block drifted:
 * only the ingest copy carried the correlation id, so a replayed delivery could not be traced
 * across the two services.
 */
@Component
@RequiredArgsConstructor
public class DeliveryDispatch {

    /** What put this Delivery on the wire. Stored on the row, and read by nothing but a human. */
    public enum Reason {
        CREATED("DeliveryCreated"),
        RETRY("DeliveryRetry"),
        REPLAYED("DeliveryReplayed"),
        BULK_REPLAYED("DeliveryBulkReplayed"),
        REPLAYED_FROM_STEP("DeliveryReplayedFromStep"),
        WORKFLOW_CREATED("WorkflowDeliveryCreated");

        private final String eventType;

        Reason(String eventType) {
            this.eventType = eventType;
        }
    }

    private final OutboxMessageRepository outboxMessageRepository;
    private final ObjectMapper objectMapper;

    /** Builds the row without saving it, for a caller that saves a batch of them at once. */
    public OutboxMessage outboxFor(Delivery delivery, UUID projectId, Reason reason) {
        try {
            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(delivery.getId())
                    .eventId(delivery.getEventId())
                    .endpointId(delivery.getEndpointId())
                    .subscriptionId(delivery.getSubscriptionId())
                    .status(delivery.getStatus().name())
                    .attemptCount(delivery.getAttemptCount())
                    .sequenceNumber(delivery.getSequenceNumber())
                    .orderingEnabled(delivery.getOrderingEnabled())
                    .build();

            return OutboxMessage.builder()
                    .aggregateType("Delivery")
                    .aggregateId(delivery.getId())
                    .eventType(reason.eventType)
                    .payload(objectMapper.writeValueAsString(message))
                    .kafkaTopic(KafkaTopics.DELIVERIES_DISPATCH)
                    .kafkaKey(delivery.getEndpointId().toString())
                    .projectId(projectId)
                    .correlationId(MDC.get("correlationId"))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create outbox message for delivery "
                    + delivery.getId(), e);
        }
    }

    public void announce(Delivery delivery, UUID projectId, Reason reason) {
        outboxMessageRepository.save(outboxFor(delivery, projectId, reason));
    }
}
