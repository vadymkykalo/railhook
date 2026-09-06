package com.webhook.platform.api.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "max_events_per_month", nullable = false)
    private long maxEventsPerMonth;

    @Column(name = "max_endpoints_per_project", nullable = false)
    private int maxEndpointsPerProject;

    @Column(name = "max_projects", nullable = false)
    private int maxProjects;

    @Column(name = "max_members", nullable = false)
    private int maxMembers;

    @Column(name = "max_active_tunnels", nullable = false)
    private int maxActiveTunnels;

    @Column(name = "max_fanout_per_event", nullable = false)
    private int maxFanoutPerEvent;

    @Column(name = "rate_limit_per_second", nullable = false)
    private int rateLimitPerSecond;

    @Column(name = "max_retention_days", nullable = false)
    private int maxRetentionDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode features;

    @Column(name = "price_monthly_cents", nullable = false)
    private int priceMonthlyCents;

    @Column(name = "price_yearly_cents", nullable = false)
    private int priceYearlyCents;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Returns true if the given limit value means "unlimited" (-1). */
    public boolean isUnlimited(long value) {
        return value == -1;
    }

    public boolean hasFeature(String featureName) {
        return features != null && features.has(featureName) && features.get(featureName).asBoolean(false);
    }
}
