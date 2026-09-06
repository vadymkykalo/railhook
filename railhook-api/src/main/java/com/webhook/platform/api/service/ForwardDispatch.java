package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Announces a Forward to the worker. The Incoming counterpart of {@link DeliveryDispatch}. */
@Component
@RequiredArgsConstructor
public class ForwardDispatch {

    public enum Reason {
        CREATED("IncomingForwardCreated"),
        REPLAY("IncomingForwardReplay"),
        BULK_REPLAY("IncomingForwardBulkReplay"),
        DLQ_RETRY("IncomingForwardDlqRetry");

        private final String eventType;

        Reason(String eventType) {
            this.eventType = eventType;
        }
    }

    private final ObjectMapper objectMapper;

    /**
     * @param replaySessionId the Replay this Forward belongs to, null for one created by ingress.
     *                        A Replay's Attempts live in their own numbering, so every claim the
     *                        worker makes is scoped to this value.
     */
    public OutboxMessage outboxFor(UUID eventId, UUID sourceId, UUID destinationId, UUID projectId,
            int attemptNumber, UUID replaySessionId, Reason reason) {
        try {
            IncomingForwardMessage message = IncomingForwardMessage.builder()
                    .incomingEventId(eventId)
                    .destinationId(destinationId)
                    .incomingSourceId(sourceId)
                    .attemptCount(attemptNumber)
                    .replay(reason != Reason.CREATED)
                    .replaySessionId(replaySessionId)
                    .build();

            return OutboxMessage.builder()
                    .aggregateType("IncomingForward")
                    .aggregateId(eventId)
                    .eventType(reason.eventType)
                    .payload(objectMapper.writeValueAsString(message))
                    .kafkaTopic(KafkaTopics.INCOMING_FORWARD_DISPATCH)
                    .kafkaKey(destinationId.toString())
                    .projectId(projectId)
                    .correlationId(MDC.get("correlationId"))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create outbox message for forward of event "
                    + eventId + " to destination " + destinationId, e);
        }
    }
}
