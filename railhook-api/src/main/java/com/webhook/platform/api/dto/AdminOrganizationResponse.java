package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.BillingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator-only. Names the owner's address so an abuse report or failed payment has a contact,
 * but carries no endpoint URLs, payloads or secrets.
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

    /** The earliest active OWNER's address. */
    private String ownerEmail;

    private long projectCount;
    private long memberCount;

    /** Whole UTC calendar month. */
    private long eventsThisMonth;
    private long eventsLimit;

    private Instant suspendedAt;
    private String suspensionReason;
    private String suspendedBy;
}
