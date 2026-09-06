package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * How long the key being replaced keeps working.
 *
 * <p>The same shape as an endpoint's signing-secret rotation, and for the same reason: a
 * rollover in which the old credential dies the instant the new one is minted is not a rollover,
 * it is an outage the customer has to schedule. Two keys are live at once for the length of the
 * window, which is exactly what lets a deployment roll.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyRotateRequest {

    /**
     * Hours the outgoing key stays valid. Defaults to 24 — a working day is enough for a
     * deployment to roll everywhere without leaving a second live credential lying around for a
     * week. Zero retires it immediately, which is what a rotation after a suspected leak wants;
     * the cap is a week, because past that the "grace window" is really two permanent keys.
     */
    @Min(value = 0, message = "gracePeriodHours cannot be negative")
    @Max(value = 168, message = "gracePeriodHours cannot exceed 168 (one week)")
    private Integer gracePeriodHours;

    /** Optional expiry for the incoming key. Absent means it does not expire. */
    private Instant expiresAt;
}
