package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A Delivery as its Consumer sees it: with the event type, which is what they recognise it by. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalDeliveryResponse {
    private UUID id;
    private UUID eventId;
    private String eventType;
    private UUID endpointId;
    private DeliveryStatus status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Instant nextRetryAt;
    private Instant lastAttemptAt;
    private Instant succeededAt;
    private Instant failedAt;
    private Instant createdAt;

    public static PortalDeliveryResponse of(Delivery delivery, String eventType) {
        return PortalDeliveryResponse.builder()
                .id(delivery.getId())
                .eventId(delivery.getEventId())
                .eventType(eventType)
                .endpointId(delivery.getEndpointId())
                .status(delivery.getStatus())
                .attemptCount(delivery.getAttemptCount())
                .maxAttempts(delivery.getMaxAttempts())
                .nextRetryAt(delivery.getNextRetryAt())
                .lastAttemptAt(delivery.getLastAttemptAt())
                .succeededAt(delivery.getSucceededAt())
                .failedAt(delivery.getFailedAt())
                .createdAt(delivery.getCreatedAt())
                .build();
    }
}
