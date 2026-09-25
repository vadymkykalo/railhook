package com.webhook.platform.worker.domain.entity;

import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.retry.RetryableStatuses;
import jakarta.persistence.*;
import lombok.*;

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

    @Id
    private UUID id;

    /** Not tenant-filtered here: the worker has no request tenant. Mapped so attempt rows can copy it. */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "delivery_origin", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DeliveryOrigin deliveryOrigin = DeliveryOrigin.SUBSCRIPTION;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Builder.Default
    @Column(name = "ordering_enabled", nullable = false)
    private Boolean orderingEnabled = false;

    /** The ordering gap timeout is measured from here, not from {@link #createdAt}. */
    @Column(name = "ordering_first_buffered_at")
    private Instant orderingFirstBufferedAt;

    /**
     * Fencing token. Status alone cannot tell an attempt's claim from a newer one after a sweep,
     * so a late response could finalise a row it no longer owns. Null while unclaimed.
     */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Builder.Default
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 30;

    @Builder.Default
    @Column(name = "retry_delays", columnDefinition = "TEXT")
    private String retryDelays = RetryLadderDefaults.OUTGOING_DELAYS;

    /**
     * Copied from the Subscription at creation, like the ladder, so an edit does not change the
     * rules for a Delivery already in flight.
     */
    @Builder.Default
    @Column(name = "retryable_statuses", nullable = false, columnDefinition = "TEXT")
    private String retryableStatuses = RetryableStatuses.DEFAULT_SPEC;

    @Column(name = "payload_template", columnDefinition = "TEXT")
    private String payloadTemplate;

    @Column(name = "custom_headers", columnDefinition = "TEXT")
    private String customHeaders;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "transformation_id")
    private UUID transformationId;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** Set by the api when a person retries the Delivery; the hard-cap escalation measures from it. */
    @Column(name = "ladder_resumed_at")
    private Instant ladderResumedAt;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** A set token means "currently claimed", which is why handing the row back clears it. */
    public void claim(UUID token) {
        Instant now = Instant.now();
        this.status = DeliveryStatus.PROCESSING;
        this.nextRetryAt = null;
        this.lastAttemptAt = now;
        this.claimToken = token;
        this.updatedAt = now;
    }

    public void handBackTo(Instant retryAt) {
        this.status = DeliveryStatus.PENDING;
        this.claimToken = null;
        this.nextRetryAt = retryAt;
        this.updatedAt = Instant.now();
    }

    public void succeed() {
        Instant now = Instant.now();
        this.status = DeliveryStatus.SUCCESS;
        this.succeededAt = now;
        this.updatedAt = now;
    }

    public void abandon() {
        Instant now = Instant.now();
        this.status = DeliveryStatus.DLQ;
        this.failedAt = now;
        this.updatedAt = now;
    }

    /** Sets {@code failedAt} like every terminal end; the status says it was not a failure. */
    public void cancel() {
        Instant now = Instant.now();
        this.status = DeliveryStatus.CANCELLED;
        this.failedAt = now;
        this.updatedAt = now;
    }

    public void failTerminally() {
        Instant now = Instant.now();
        this.status = DeliveryStatus.FAILED;
        this.failedAt = now;
        this.updatedAt = now;
    }

    public enum DeliveryStatus {
        PENDING, PROCESSING, SUCCESS, FAILED, DLQ,
        CANCELLED
    }

    public enum DeliveryOrigin {
        SUBSCRIPTION, RULE
    }
}
