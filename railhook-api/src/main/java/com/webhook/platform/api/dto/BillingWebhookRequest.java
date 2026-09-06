package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingWebhookRequest {
    private String eventType;
    private String externalCustomerId;
    private String planName;
    private String providerEventId;
}
