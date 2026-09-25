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
     * Fencing token of the claim that moved this row to PROCESSING. Finalising writes only while
     * it still matches, so an attempt taken away by the stuck sweep cannot finalise a reclaimed
     * row. Null when unclaimed.
     */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Shown in the dashboard, so the Destination's credentials are masked before they land here. */
    @Column(name = "request_headers_json", columnDefinition = "TEXT")
    private String requestHeadersJson;

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
     * Null for a Forward created by ingress. A Replay's ladder restarts at attempt 1, so every
     * claim is scoped to this value to keep Replays from claiming each other's rows.
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
