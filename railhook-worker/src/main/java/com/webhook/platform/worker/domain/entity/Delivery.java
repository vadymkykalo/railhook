package com.webhook.platform.worker.domain.entity;

import com.webhook.platform.common.retry.RetryLadderDefaults;
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

    /**
     * When this delivery was first buffered waiting on a missing predecessor sequence.
     * Null if it has never been buffered. Drives the gap timeout in
     * {@code OrderingBufferService#isGapTimedOut} — measured from here, not from
     * {@link #createdAt}.
     */
    @Column(name = "ordering_first_buffered_at")
    private Instant orderingFirstBufferedAt;

    /**
     * Fencing token stamped by whichever claim moved this delivery to PROCESSING.
     *
     * <p>Guarding a finalizer on {@code status == PROCESSING} alone cannot tell the claim
     * it is finishing apart from a newer claim on the same row: an attempt whose worker
     * looked dead can be swept back to PENDING, reclaimed by a different attempt, and then
     * have its own late response arrive and finalize a row it no longer owns. Comparing
     * this token against the one the attempt started under closes that window. Null while
     * the delivery is unclaimed.
     */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Builder.Default
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 30;

    @Builder.Default
    @Column(name = "retry_delays", columnDefinition = "TEXT")
    private String retryDelays = RetryLadderDefaults.OUTGOING_DELAYS;

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

    /**
     * Takes the row for one Attempt. The token is what a later writer must still match to
     * finalise: "token set" means "currently claimed", which is why handing the row back clears it.
     */
    public void claim(UUID token) {
        Instant now = Instant.now();
        this.status = DeliveryStatus.PROCESSING;
        this.nextRetryAt = null;
        this.lastAttemptAt = now;
        this.claimToken = token;
        this.updatedAt = now;
    }

    /** Ends the Claim and returns the obligation to the retry ladder, to be picked up at {@code retryAt}. */
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

    /** The Retry Ladder is exhausted: kept for a human to decide about. */
    public void abandon() {
        Instant now = Instant.now();
        this.status = DeliveryStatus.DLQ;
        this.failedAt = now;
        this.updatedAt = now;
    }

    /** No number of retries would help — a refused URL, a deleted endpoint, an unusable ladder. */
    public void failTerminally() {
        Instant now = Instant.now();
        this.status = DeliveryStatus.FAILED;
        this.failedAt = now;
        this.updatedAt = now;
    }

    public enum DeliveryStatus {
        PENDING, PROCESSING, SUCCESS, FAILED, DLQ
    }

    public enum DeliveryOrigin {
        SUBSCRIPTION, RULE
    }
}
