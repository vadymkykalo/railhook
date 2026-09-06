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
 * It carries no member emails and no endpoint URLs — answering "who is this and are they in
 * trouble" needs neither, and a support view that shows customer data by default becomes a
 * reason not to give anyone the credential.
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

    private long projectCount;
    private long memberCount;

    /** Null when the organization is not suspended, which is the ordinary case. */
    private Instant suspendedAt;
    private String suspensionReason;
    private String suspendedBy;
}
