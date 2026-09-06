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
     * When an operator suspended this organization, or null.
     *
     * <p>Deliberately not {@code billingStatus.SUSPENDED}. That value belongs to the payment
     * state machine — the dunning scheduler writes it when a grace period expires, and the
     * subscription lifecycle overwrites it on the next sync — so an abuse suspension stored
     * there would be lifted by a successful payment. It is also read by nothing, which is the
     * other half of why suspension did not suspend anything.
     */
    @Column(name = "suspended_at")
    private Instant suspendedAt;

    /** Why, in the operator's words. Shown to the tenant in the refusal. */
    @Column(name = "suspension_reason")
    private String suspensionReason;

    /**
     * Who suspended it, as free text. The platform-admin credential is one shared token with no
     * identity of its own, so this is whatever the operator chose to write down — worth having
     * anyway, because the alternative is a suspension nobody can attribute.
     */
    @Column(name = "suspended_by")
    private String suspendedBy;

    public boolean isSuspended() {
        return suspendedAt != null;
    }
}
