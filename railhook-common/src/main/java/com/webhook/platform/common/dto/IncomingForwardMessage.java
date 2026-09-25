package com.webhook.platform.common.dto;

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
public class IncomingForwardMessage {
    private UUID incomingEventId;
    private UUID destinationId;
    private UUID incomingSourceId;
    private Integer attemptCount;
    private boolean replay;

    /**
     * A Replay starts a second Retry Ladder for the same (Incoming Event, Destination), so claims
     * are scoped to it. Without it two Replays would claim each other's rows and both POST. Null
     * for ingress Forwards and for older producers, which want the same handling.
     */
    private UUID replaySessionId;

    /**
     * Fencing token: the {@code started_at} the retry scheduler stamped when it claimed the row.
     * The consumer CAS-claims on it, so a redelivered copy of this Kafka message is rejected
     * instead of POSTing twice. Null from older producers, where PROCESSING alone is trusted.
     */
    private Instant startedAt;
}
