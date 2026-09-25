package com.webhook.platform.api.domain.entity;

import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.retry.RetryableStatuses;
import com.webhook.platform.api.domain.enums.DeliveryOrigin;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delivery {

    /**
     * A manual retry does not restart the ladder, so these Attempts wait at the tier already
     * reached. Three still fit inside the worker's 96-hour hard cap at the top of the jitter.
     */
    public static final int MANUAL_RETRY_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_origin", nullable = false)
    @Builder.Default
    private DeliveryOrigin deliveryOrigin = DeliveryOrigin.SUBSCRIPTION;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 7;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "ordering_enabled", nullable = false)
    @Builder.Default
    private Boolean orderingEnabled = false;

    /** Written only by the worker; mapped here so schema validation passes. */
    @Column(name = "ordering_first_buffered_at")
    private Instant orderingFirstBufferedAt;

    /**
     * Fencing token. A status check alone cannot tell a late response from a swept-and-reclaimed
     * attempt apart from the current claim, so finalizers compare this token.
     */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "timeout_seconds")
    @Builder.Default
    private Integer timeoutSeconds = 30;

    @Column(name = "retry_delays", columnDefinition = "TEXT")
    @Builder.Default
    private String retryDelays = RetryLadderDefaults.OUTGOING_DELAYS;

    /** Copied from the Subscription at creation, like the ladder, so an edit cannot change a Delivery in flight. */
    @Column(name = "retryable_statuses", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String retryableStatuses = RetryableStatuses.DEFAULT_SPEC;

    @Column(name = "payload_template", columnDefinition = "TEXT")
    private String payloadTemplate;

    @Column(name = "custom_headers", columnDefinition = "TEXT")
    private String customHeaders;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "transformation_id")
    private UUID transformationId;

    @Column(name = "replay_session_id")
    private UUID replaySessionId;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** The worker's hard-cap escalation measures age from here when set, else from createdAt. */
    @Column(name = "ladder_resumed_at")
    private Instant ladderResumedAt;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * attemptCount is never reset: the ladder reads it and every recorded Attempt is numbered by
     * it. The headroom comes from maxAttempts, capped at the largest ladder the worker accepts.
     */
    public void returnToLadder(int additionalAttempts) {
        this.status = DeliveryStatus.PENDING;
        this.maxAttempts = Math.min(attemptCount + additionalAttempts, RetryLadder.MAX_ATTEMPTS_LIMIT);
        this.nextRetryAt = null;
        this.failedAt = null;
        this.ladderResumedAt = Instant.now();
        // The worker leaves its token behind; kept, the retry looks claimed and a backpressure
        // reschedule (claim_token IS NULL) never matches it.
        this.claimToken = null;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", insertable = false, updatable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "endpoint_id", insertable = false, updatable = false)
    private Endpoint endpoint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", insertable = false, updatable = false)
    private Subscription subscription;
}
