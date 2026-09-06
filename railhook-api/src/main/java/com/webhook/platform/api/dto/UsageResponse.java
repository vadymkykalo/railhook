package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageResponse {

    private ResourceUsage events;
    private ResourceUsage endpoints;
    private ResourceUsage projects;
    private ResourceUsage members;
    private int rateLimitPerSecond;
    private int retentionDays;
    private Instant periodStart;
    private Instant periodEnd;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ResourceUsage {
        private long current;
        private long limit;
        private double percentUsed;
    }
}
