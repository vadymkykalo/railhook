package com.webhook.platform.common.enums;

public enum ForwardAttemptStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    DLQ,
    /**
     * A Transformation chose not to send. Terminal, and not the DLQ: there is nothing to fix.
     */
    CANCELLED
}
