package com.webhook.platform.api.domain.entity;

import com.webhook.platform.common.retry.RetryLadderDefaults;
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

    /**
     * When this delivery was first buffered by the worker waiting on a missing predecessor
     * sequence. Null if it has never been buffered. Written only by the worker; kept here
     * purely so Hibernate schema validation (ddl-auto=validate) passes on this side too.
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

    @Column(name = "timeout_seconds")
    @Builder.Default
    private Integer timeoutSeconds = 30;

    @Column(name = "retry_delays", columnDefinition = "TEXT")
    @Builder.Default
    private String retryDelays = RetryLadderDefaults.OUTGOING_DELAYS;

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
