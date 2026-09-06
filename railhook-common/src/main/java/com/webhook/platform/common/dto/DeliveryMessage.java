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
     * The fencing token the retry scheduler held when it published this message.
     *
     * <p>Only set on the retry path, and only by RetrySchedulerService. The consumer claims
     * the row by swapping this token for a fresh one, so a message carrying a token the row
     * no longer has loses the race and does not dispatch.</p>
     *
     * <p>Null on first dispatch, where the consumer claims PENDING -> PROCESSING itself and
     * there is no prior token to match. Also null on a retry message published by a worker
     * from before this field existed, which the consumer treats as the old
     * trust-the-status behaviour rather than dropping every in-flight retry across a rolling
     * deploy — the same accommodation IncomingAttemptStore makes for its own token.</p>
     */
    private UUID claimToken;
}
