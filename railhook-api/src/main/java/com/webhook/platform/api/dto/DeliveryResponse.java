package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryResponse {
    private UUID id;
    private UUID eventId;
    private String eventType;
    private UUID endpointId;
    private UUID subscriptionId;
    private DeliveryStatus status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Instant nextRetryAt;
    private Instant lastAttemptAt;
    private Instant succeededAt;
    private Instant failedAt;
    private Instant createdAt;

    public static DeliveryResponse of(Delivery delivery) {
        return of(delivery, null);
    }

    public static DeliveryResponse of(Delivery delivery, String eventType) {
        return DeliveryResponse.builder()
                .id(delivery.getId())
                .eventId(delivery.getEventId())
                .eventType(eventType)
                .endpointId(delivery.getEndpointId())
                .subscriptionId(delivery.getSubscriptionId())
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
