package com.webhook.platform.worker.domain.entity;

import com.webhook.platform.common.enums.ForwardAttemptStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "incoming_forward_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomingForwardAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Not tenant-filtered here: the worker has no request tenant. Mapped so attempt rows can copy it. */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "incoming_event_id", nullable = false)
    private UUID incomingEventId;

    @Column(name = "destination_id", nullable = false)
    private UUID destinationId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ForwardAttemptStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    /** Fencing token, so an attempt swept away by the stuck sweep cannot finalise a reclaimed row. */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Sanitised: shown in the dashboard, so the Destination's credentials must be masked. */
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
     * claim is scoped to this or two ladders would claim each other's rows.
     */
    @Column(name = "replay_session_id")
    private UUID replaySessionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Truncated to microseconds as Postgres stores it, or the CAS on started_at never matches. */
    public void claimForRetry() {
        this.status = ForwardAttemptStatus.PROCESSING;
        this.startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.nextRetryAt = null;
    }

    /** {@code next_retry_at} must be set: the scheduler ignores rows without one. */
    public void handBackTo(Instant retryAt) {
        this.status = ForwardAttemptStatus.PENDING;
        this.startedAt = null;
        this.claimToken = null;
        this.nextRetryAt = retryAt;
    }

    public void abandon(String reason) {
        this.status = ForwardAttemptStatus.DLQ;
        this.finishedAt = Instant.now();
        this.errorMessage = reason;
        this.nextRetryAt = null;
    }

    public void failWith(String reason) {
        this.status = ForwardAttemptStatus.FAILED;
        this.finishedAt = Instant.now();
        this.errorMessage = reason;
        this.nextRetryAt = null;
    }
}
