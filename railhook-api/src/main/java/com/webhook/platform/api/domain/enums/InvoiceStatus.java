package com.webhook.platform.api.domain.enums;

/**
 * No VOID or UNCOLLECTIBLE: those are decisions made in the provider's dashboard, which Railhook
 * never hears about.
 */
public enum InvoiceStatus {
    DRAFT,
    OPEN,
    PAID,
    PAST_DUE
}
