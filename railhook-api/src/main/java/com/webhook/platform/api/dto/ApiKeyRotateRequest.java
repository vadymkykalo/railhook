package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyRotateRequest {

    /** Defaults to 24. Zero retires the old key immediately, for a suspected leak. */
    @Min(value = 0, message = "gracePeriodHours cannot be negative")
    @Max(value = 168, message = "gracePeriodHours cannot exceed 168 (one week)")
    private Integer gracePeriodHours;

    private Instant expiresAt;
}
