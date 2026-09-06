package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsResponse {
    private DeliveryStats deliveryStats;
    private List<RecentEventSummary> recentEvents;
    private List<EndpointHealthSummary> endpointHealth;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryStats {
        private long totalDeliveries;
        private long successfulDeliveries;
        private long failedDeliveries;
        private long pendingDeliveries;
        private long dlqDeliveries;
        private double successRate;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentEventSummary {
        private String id;
        private String type;
        private String createdAt;
        private int deliveryCount;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointHealthSummary {
        private String id;
        private String url;
        private boolean enabled;
        private long totalDeliveries;
        private long successfulDeliveries;
        private double successRate;
    }
}
