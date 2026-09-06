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

    /**
     * Tenant discriminator, mapped but not enforced here: the api filters on this column via
     * {@code @TenantId}, the worker deliberately does not — it has no {@code AuthContext} and
     * every consumer is a system path. It is mapped rather than ignored because the attempt
     * stores have to carry the tenant across from the parent row, and because
     * {@code EntityMappingParityIntegrationTest} requires both modules to map every column of a
     * shared table.
     */
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

    /**
     * Takes the row for one retry Attempt. {@code started_at} is truncated to microseconds because
     * Postgres stores it that way, and a full-nanosecond Instant would not match on the CAS that
     * claims the row back.
     */
    public void claimForRetry() {
        this.status = ForwardAttemptStatus.PROCESSING;
        this.startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.nextRetryAt = null;
    }

    /**
     * Ends the Claim and returns the Forward to the retry ladder. {@code next_retry_at} must be
     * set: the scheduler ignores rows without one, so a hand-back that skips it strands the
     * Forward.
     */
    public void handBackTo(Instant retryAt) {
        this.status = ForwardAttemptStatus.PENDING;
        this.startedAt = null;
        this.claimToken = null;
        this.nextRetryAt = retryAt;
    }

    /** The Retry Ladder is exhausted: kept for a human to decide about. */
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
