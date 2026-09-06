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
    private UUID endpointId;
    private UUID subscriptionId;
    /**
     * Typed rather than a String, so the published spec carries the enum. Jackson writes an
     * enum as its name, so the JSON is byte-for-byte what it was; what changes is that
     * openapi.yaml now says which five values this can be, the generated TypeScript narrows
     * from string to a union, and the locale ratchet has something to check the delivery
     * status labels against - the most-rendered status in the product, and the one namespace
     * the drift test structurally could not cover.
     */
    private DeliveryStatus status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Instant nextRetryAt;
    private Instant lastAttemptAt;
    private Instant succeededAt;
    private Instant failedAt;
    private Instant createdAt;

    public static DeliveryResponse of(Delivery delivery) {
        return DeliveryResponse.builder()
                .id(delivery.getId())
                .eventId(delivery.getEventId())
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
