package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.EndpointHealth;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsResponse {
    private TimeRange timeRange;
    private OverviewMetrics overview;
    private List<TimeSeriesPoint> deliveryTimeSeries;
    private List<TimeSeriesPoint> latencyTimeSeries;
    private List<EventTypeBreakdown> eventTypeBreakdown;
    private List<EndpointPerformance> endpointPerformance;
    private LatencyPercentiles latencyPercentiles;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeRange {
        private String from;
        private String to;
        private String granularity; // HOUR, DAY, WEEK
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverviewMetrics {
        private long totalEvents;
        private long totalDeliveries;
        private long successfulDeliveries;
        private long failedDeliveries;
        private double successRate;
        private double avgLatencyMs;
        private long p50LatencyMs;
        private long p95LatencyMs;
        private long p99LatencyMs;
        private double eventsPerSecond;
        private double deliveriesPerSecond;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSeriesPoint {
        private String timestamp;
        private long total;
        private long success;
        private long failed;
        private Double avgLatencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventTypeBreakdown {
        private String eventType;
        private long count;
        private double percentage;
        private long successCount;
        private double successRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointPerformance {
        private String endpointId;
        private String url;
        private boolean enabled;
        private long totalDeliveries;
        private long successfulDeliveries;
        private long failedDeliveries;
        private double successRate;
        private double avgLatencyMs;
        private long p95LatencyMs;
        private String lastDeliveryAt;
        private EndpointHealth status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatencyPercentiles {
        private long p50;
        private long p75;
        private long p90;
        private long p95;
        private long p99;
        private long max;
    }
}
