package com.webhook.platform.api.domain.enums;

/**
 * What became of one charge.
 *
 * <p>No {@code PROCESSING}: a provider tells Railhook about a charge once it has resolved, so a
 * payment row goes straight from {@code PENDING} to {@code SUCCEEDED} or {@code FAILED} and there
 * is no signal that would move it to an in-flight state.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    /** The whole amount came back. */
    REFUNDED,
    /** Some of it did — {@code refundedCents} is below {@code amountCents}. */
    PARTIALLY_REFUNDED
}
