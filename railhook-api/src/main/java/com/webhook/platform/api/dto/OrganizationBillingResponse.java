package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.BillingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationBillingResponse {
    private UUID organizationId;
    private PlanResponse plan;
    private BillingStatus billingStatus;
    private String billingEmail;
    private UsageSnapshot usage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UsageSnapshot {
        private long eventsThisMonth;
        private long eventsLimit;
        private long projects;
        private long projectsLimit;
    }
}
