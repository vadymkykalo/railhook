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
     * The Replay this Forward belongs to, null for one created by ingress.
     *
     * <p>A Replay starts a second Retry Ladder for the same (Incoming Event, Destination) pair,
     * so an attempt number alone no longer names one row. Every claim is scoped to this value;
     * without it two Replays of the same Incoming Event would claim each other's attempt rows and
     * both POST. Null for messages published by an older producer, which is also what an ingress
     * Forward carries, and the two want the same treatment.</p>
     */
    private UUID replaySessionId;

    /**
     * Fencing token for the retry path: the {@code started_at} value the retry
     * scheduler stamped on the attempt row when it claimed PENDING -> PROCESSING and
     * published this message. IncomingForwardService CAS-claims on this value before
     * dispatching, so a duplicate delivery of this exact Kafka message (offset commit lost
     * on a rebalance, at-least-once redelivery, ...) finds the token already consumed and
     * is rejected instead of double-POSTing to the destination. Null for messages published
     * by an older producer (rolling deploy) — the consumer falls back to the pre-existing
     * "PROCESSING is enough" behavior in that case.
     */
    private Instant startedAt;
}
