package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.AlertSeverity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    /**
     * The rule that fired — or null for an event Railhook raised on its own, which today means
     * an endpoint auto-disabled for continuous failure. Nullable rather than hung off a hidden
     * per-organization rule, which would put a row in the alert-rules list that nobody can
     * explain or delete.
     */
    @Column(name = "alert_rule_id")
    private UUID alertRuleId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** The Endpoint this event is about, when it is about one. */
    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertSeverity severity;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "current_value")
    private Double currentValue;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
