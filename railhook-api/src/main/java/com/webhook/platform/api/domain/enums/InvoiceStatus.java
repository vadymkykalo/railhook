package com.webhook.platform.api.domain.enums;

/**
 * Where one invoice stands. Written by {@code BillingSchedulerService}, which drafts an invoice
 * for a renewal, opens it when the charge is attempted, and then marks it paid or past due.
 *
 * <p>No {@code VOID} or {@code UNCOLLECTIBLE}: both are Stripe vocabulary for decisions a human
 * makes in the provider's own dashboard, and Railhook neither offers that decision nor hears
 * about it — nothing could ever have set them.
 */
public enum InvoiceStatus {
    DRAFT,
    OPEN,
    PAID,
    PAST_DUE
}
