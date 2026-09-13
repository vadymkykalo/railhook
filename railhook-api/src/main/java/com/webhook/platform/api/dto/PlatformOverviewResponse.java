package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * The deployment at a glance, for the platform admin.
 *
 * <p>Counts only, plus the most recent sign-ups. "Today" is the UTC day; "this month" is the
 * billing period the tenant's own usage page measures, so a near-quota count here and a customer's
 * usage bar agree.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformOverviewResponse {

    private long organizations;
    private long suspendedOrganizations;
    private long users;

    private long signupsToday;
    private long signups7d;
    private long signups30d;

    private long eventsToday;
    private long events30d;

    private long deliveriesSucceeded24h;
    /** FAILED (still retrying) and DLQ (given up), created in the last 24 hours. */
    private long deliveriesFailed24h;

    private long activeTunnels;

    /** Organizations whose events this billing period are at 80% or more of their plan's limit. */
    private long organizationsNearQuota;

    private List<AdminSignupResponse> recentSignups;

    private Instant generatedAt;
}
