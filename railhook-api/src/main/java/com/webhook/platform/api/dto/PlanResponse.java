package com.webhook.platform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.api.domain.entity.Plan;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanResponse {
    private UUID id;
    private String name;
    private String displayName;
    private long maxEventsPerMonth;
    private int maxEndpointsPerProject;
    private int maxProjects;
    private int maxMembers;
    private int maxActiveTunnels;
    private int rateLimitPerSecond;
    private int maxRetentionDays;
    private JsonNode features;
    private int priceMonthlyCents;
    private int priceYearlyCents;

    public static PlanResponse of(Plan plan) {
        return PlanResponse.builder()
                .id(plan.getId())
                .name(plan.getName())
                .displayName(plan.getDisplayName())
                .maxEventsPerMonth(plan.getMaxEventsPerMonth())
                .maxEndpointsPerProject(plan.getMaxEndpointsPerProject())
                .maxProjects(plan.getMaxProjects())
                .maxMembers(plan.getMaxMembers())
                .maxActiveTunnels(plan.getMaxActiveTunnels())
                .rateLimitPerSecond(plan.getRateLimitPerSecond())
                .maxRetentionDays(plan.getMaxRetentionDays())
                .features(plan.getFeatures())
                .priceMonthlyCents(plan.getPriceMonthlyCents())
                .priceYearlyCents(plan.getPriceYearlyCents())
                .build();
    }
}
