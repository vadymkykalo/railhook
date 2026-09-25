package com.webhook.platform.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryMessage {
    private UUID deliveryId;
    private UUID eventId;
    private UUID endpointId;
    private UUID subscriptionId;
    private String status;
    private Integer attemptCount;
    private Long sequenceNumber;
    private Boolean orderingEnabled;

    /**
     * Set only by the retry scheduler. The consumer claims the row by swapping this token for a
     * fresh one, so a stale copy of the message loses and does not dispatch. Null on first
     * dispatch, and from older workers, which fall back to trusting the status.
     */
    private UUID claimToken;
}
