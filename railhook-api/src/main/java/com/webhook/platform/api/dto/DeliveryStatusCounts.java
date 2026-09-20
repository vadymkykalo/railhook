package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How many of one Event's Deliveries are in each status right now. Every status is present, zero
 * included, so a reader never has to tell "none" from "not reported".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryStatusCounts {
    private int pending;
    private int processing;
    private int success;
    private int failed;
    private int dlq;

    /** Deliveries a Transformation said not to send. Neither delivered nor failed. */
    private int cancelled;
}
