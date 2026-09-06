package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "replay_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplaySession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReplaySessionStatus status = ReplaySessionStatus.PENDING;

    // --- Filter criteria (immutable after creation) ---

    @Column(name = "from_date", nullable = false)
    private Instant fromDate;

    @Column(name = "to_date", nullable = false)
    private Instant toDate;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_status")
    private DeliveryStatus sourceStatus;

    // --- Progress tracking ---

    @Column(name = "total_events", nullable = false)
    @Builder.Default
    private Integer totalEvents = 0;

    @Column(name = "processed_events", nullable = false)
    @Builder.Default
    private Integer processedEvents = 0;

    @Column(name = "deliveries_created", nullable = false)
    @Builder.Default
    private Integer deliveriesCreated = 0;

    @Column(name = "errors", nullable = false)
    @Builder.Default
    private Integer errors = 0;

    @Column(name = "last_processed_event_id")
    private UUID lastProcessedEventId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // --- Timing ---

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // --- Optimistic locking ---

    @Version
    @Column(nullable = false)
    private Integer version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", insertable = false, updatable = false)
    private Project project;
}
