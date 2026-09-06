package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.ScheduledChangeStatus;
import com.webhook.platform.api.domain.enums.ScheduledChangeType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "billing_scheduled_changes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingScheduledChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "from_plan_id", nullable = false)
    private UUID fromPlanId;

    @Column(name = "to_plan_id", nullable = false)
    private UUID toPlanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30)
    @Builder.Default
    private ScheduledChangeType changeType = ScheduledChangeType.PLAN_CHANGE;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ScheduledChangeStatus status = ScheduledChangeStatus.PENDING;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
