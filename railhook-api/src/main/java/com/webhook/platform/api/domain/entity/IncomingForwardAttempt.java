package com.webhook.platform.api.domain.entity;

import com.webhook.platform.common.enums.ForwardAttemptStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incoming_forward_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class IncomingForwardAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "incoming_event_id", nullable = false)
    private UUID incomingEventId;

    @Column(name = "destination_id", nullable = false)
    private UUID destinationId;

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ForwardAttemptStatus status = ForwardAttemptStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * Fencing token for whichever claim moved this row to PROCESSING (V060).
     *
     * <p>{@code finalise} writes only while this still matches the token its own attempt was
     * claimed under, so an attempt a stuck sweep has already taken away cannot finalize a row
     * that has since been reclaimed. Null when unclaimed. The outgoing counterpart is
     * {@code deliveries.claim_token} (V055).</p>
     */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * Headers as they went to the Destination, already sanitised: this is shown in the
     * dashboard, so the Destination's own credentials must be masked before they land here.
     */
    @Column(name = "request_headers_json", columnDefinition = "TEXT")
    private String requestHeadersJson;

    /** The transformed body actually sent, capped the way {@code delivery_attempts} caps its own. */
    @Column(name = "request_body_snippet", columnDefinition = "TEXT")
    private String requestBodySnippet;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "response_headers_json", columnDefinition = "TEXT")
    private String responseHeadersJson;

    @Column(name = "response_body_snippet", columnDefinition = "TEXT")
    private String responseBodySnippet;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /**
     * The Replay this Forward belongs to, null for one created by ingress (V064).
     *
     * <p>A Replay builds a fresh Forward with its own ladder starting at attempt 1, so its rows
     * would otherwise collide by attempt number with the live ladder's. Every claim is scoped to
     * this value, which is what stops two Replays of the same Incoming Event to the same
     * Destination claiming each other's rows.</p>
     */
    @Column(name = "replay_session_id")
    private UUID replaySessionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incoming_event_id", insertable = false, updatable = false)
    private IncomingEvent incomingEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_id", insertable = false, updatable = false)
    private IncomingDestination destination;
}
