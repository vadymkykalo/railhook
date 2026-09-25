package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Every status is present, zero included, so "none" is never confused with "not reported". */
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

    /** Suppressed by a Transformation: neither delivered nor failed. */
    private int cancelled;
}
