package com.webhook.platform.api.domain.enums;

/** No PROCESSING: providers report a charge only once it has resolved. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED
}
