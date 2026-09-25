package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * "Today" is the UTC day; "this month" is the billing period, so near-quota counts agree with the
 * tenant's own usage page.
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
    // FAILED and DLQ both count.
    private long deliveriesFailed24h;

    private long activeTunnels;

    // At 80% or more of the plan's event limit.
    private long organizationsNearQuota;

    // Oldest first, with every day present.
    private List<Day> daily30d;

    private Activation activation30d;

    private List<AdminSignupResponse> recentSignups;

    private Instant generatedAt;

    // A funnel: each step is a subset of the one before. From organizations on, it counts
    // organizations, because projects and events belong to the organization.
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Activation {
        private long signups;
        private long verified;
        private long organizations;
        private long withProject;
        private long withEvent;
    }

    // The database's calendar day.
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {
        private LocalDate date;
        private long signups;
        private long events;
    }
}
