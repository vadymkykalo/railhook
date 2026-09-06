package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "usage_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "events_count", nullable = false)
    @Builder.Default
    private Long eventsCount = 0L;

    @Column(name = "deliveries_count", nullable = false)
    @Builder.Default
    private Long deliveriesCount = 0L;

    @Column(name = "successful_deliveries", nullable = false)
    @Builder.Default
    private Long successfulDeliveries = 0L;

    @Column(name = "failed_deliveries", nullable = false)
    @Builder.Default
    private Long failedDeliveries = 0L;

    @Column(name = "dlq_count", nullable = false)
    @Builder.Default
    private Long dlqCount = 0L;

    @Column(name = "incoming_events_count", nullable = false)
    @Builder.Default
    private Long incomingEventsCount = 0L;

    @Column(name = "incoming_forwards_count", nullable = false)
    @Builder.Default
    private Long incomingForwardsCount = 0L;

    @Column(name = "avg_latency_ms")
    private Double avgLatencyMs;

    @Column(name = "p95_latency_ms")
    private Double p95LatencyMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
