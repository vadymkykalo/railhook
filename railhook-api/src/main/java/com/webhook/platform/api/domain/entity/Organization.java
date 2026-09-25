package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.BillingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(name = "billing_email")
    private String billingEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_status", nullable = false, length = 30)
    @Builder.Default
    private BillingStatus billingStatus = BillingStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Separate from {@code BillingStatus.SUSPENDED}, which the billing sync overwrites, so a
     * successful payment would lift an abuse suspension stored there.
     */
    @Column(name = "suspended_at")
    private Instant suspendedAt;

    /** Shown to the tenant in the refusal. */
    @Column(name = "suspension_reason")
    private String suspensionReason;

    /** Free text: the platform-admin credential is a shared token with no identity. */
    @Column(name = "suspended_by")
    private String suspendedBy;

    public boolean isSuspended() {
        return suspendedAt != null;
    }
}
