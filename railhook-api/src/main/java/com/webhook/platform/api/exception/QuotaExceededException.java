package com.webhook.platform.api.exception;

import lombok.Getter;

/**
 * Thrown when an organization exceeds a plan quota (events, endpoints, projects, etc.).
 * Maps to HTTP 402 Payment Required.
 */
@Getter
public class QuotaExceededException extends RuntimeException {

    private final String quotaName;
    private final long currentUsage;
    private final long limit;
    private final String planName;

    public QuotaExceededException(String quotaName, long currentUsage, long limit, String planName) {
        super(String.format("Quota exceeded: %s (%d / %d). Current plan: %s. Please upgrade.",
                quotaName, currentUsage, limit, planName));
        this.quotaName = quotaName;
        this.currentUsage = currentUsage;
        this.limit = limit;
        this.planName = planName;
    }
}
