package com.webhook.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {
    @NotBlank
    private String planName;
    @NotBlank
    private String successUrl;
    @NotBlank
    private String cancelUrl;

    private String providerCode;

    private String billingInterval;
}
