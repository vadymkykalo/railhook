package com.webhook.platform.api.domain.enums;

public enum DeliveryStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    DLQ,
    /**
     * A Transformation chose not to send. Terminal, and not in the DLQ because there is nothing
     * to fix; the reason is on the Attempt.
     */
    CANCELLED
}
