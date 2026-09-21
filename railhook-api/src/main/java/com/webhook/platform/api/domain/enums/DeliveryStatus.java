package com.webhook.platform.api.domain.enums;

public enum DeliveryStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    DLQ,
    /**
     * A Transformation said not to send this one.
     *
     * <p>Terminal and deliberate, which is why it is neither SUCCESS nor FAILED: nothing reached
     * the target, and nothing went wrong. It is not in the DLQ either — the DLQ is for what a
     * person could still fix, and there is nothing here to fix. The reason the script gave is on
     * the Attempt.
     */
    CANCELLED
}
