package com.webhook.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OnboardingStatusResponse {
    private boolean hasEndpoints;
    private boolean hasSubscriptions;
    private boolean hasApiKeys;
    private boolean hasEvents;
    private boolean hasDeliveries;
    private boolean hasIncomingSources;
    private boolean hasIncomingDestinations;
}
