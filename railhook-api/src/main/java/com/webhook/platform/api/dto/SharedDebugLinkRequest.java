package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SharedDebugLinkRequest {

    @Min(value = 1, message = "Expiry hours must be at least 1")
    @Max(value = 168, message = "Expiry hours must not exceed 168 (7 days)")
    @Builder.Default
    private Integer expiryHours = 24;
}
