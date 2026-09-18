package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
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

    /** Sign-ups and events per day over the last 30 days, oldest first, every day present. */
    private List<Day> daily30d;

    /** How far the last 30 days' sign-ups got. */
    private Activation activation30d;

    private List<AdminSignupResponse> recentSignups;

    private Instant generatedAt;

    /**
     * The first steps of the people who signed up in the window, each a subset of the one before:
     * accounts and how many verified their address; organizations and how many created a project
     * and then sent an event. Organizations rather than accounts from there on, because a project
     * and its events belong to the organization, whichever member did the work.
     */
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

    /** One calendar day of {@link #daily30d}, the database's day. */
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
