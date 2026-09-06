package com.webhook.platform.api.service.billing;

/**
 * Declares what a billing provider can do.
 * Used by BillingService to decide whether to delegate to provider or handle internally.
 */
public enum BillingCapability {
    /** Provider manages subscription lifecycle (billing cycles, retries, dunning) — e.g. Stripe */
    MANAGED_SUBSCRIPTIONS,
    /** Provider supports merchant-initiated recurring charges via stored token — e.g. WayForPay */
    MERCHANT_RECURRING,
    /** Provider offers a self-service customer portal — e.g. Stripe Customer Portal */
    CUSTOMER_PORTAL,
    /** Provider can list/manage invoices externally */
    EXTERNAL_INVOICES,
    /** Provider supports creating customers as a first-class entity */
    CUSTOMERS
}
