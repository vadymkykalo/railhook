package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayEstimateResponse {

    private Long totalEvents;
    private Long estimatedDeliveries;
    private Integer activeSubscriptions;
    private String warning;
}
