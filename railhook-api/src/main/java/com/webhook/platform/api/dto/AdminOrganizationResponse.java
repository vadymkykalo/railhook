package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.BillingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One organization as the operator sees it.
 *
 * <p>Deliberately not the tenant-facing {@code OrganizationResponse}: this carries the plan,
 * the billing status and the suspension, which is the whole reason an operator opens the list.
 *
 * <p>It names one person — the owner, by address — because "who do I contact about this
 * organization" is the first question an abuse report or a failed payment raises, and the list is
 * reached only by a named, verified, audited platform admin or the operator token. It carries no
 * endpoint URLs, payloads or secrets: counts and limits only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOrganizationResponse {

    private UUID id;
    private String name;
    private String planName;
    private BillingStatus billingStatus;
    private Instant createdAt;

    /** The earliest active OWNER's address; null only for an organization left without one. */
    private String ownerEmail;

    private long projectCount;
    private long memberCount;

    /** Events in the current billing period (whole UTC month), against the plan's monthly limit. */
    private long eventsThisMonth;
    private long eventsLimit;

    /** Null when the organization is not suspended, which is the ordinary case. */
    private Instant suspendedAt;
    private String suspensionReason;
    private String suspendedBy;
}
