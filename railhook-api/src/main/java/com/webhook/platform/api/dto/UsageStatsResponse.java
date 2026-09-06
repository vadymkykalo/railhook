package com.webhook.platform.api.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageStatsResponse {
    private LiveUsage current;
    private List<DailyUsage> history;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LiveUsage {
        private long totalEvents;
        private long totalDeliveries;
        private long successfulDeliveries;
        private long failedDeliveries;
        private long dlqDeliveries;
        private long pendingDeliveries;
        private long totalIncomingEvents;
        private long totalIncomingForwards;
        private long activeEndpoints;
        private long activeIncomingSources;
        private long activeAlertRules;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DailyUsage {
        private String date;
        private long eventsCount;
        private long deliveriesCount;
        private long successfulDeliveries;
        private long failedDeliveries;
        private long dlqCount;
        private long incomingEventsCount;
        private long incomingForwardsCount;
        private Double avgLatencyMs;
        private Double p95LatencyMs;
    }
}
