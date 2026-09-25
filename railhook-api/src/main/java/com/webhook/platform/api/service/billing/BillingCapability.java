package com.webhook.platform.api.service.billing;

public enum BillingCapability {
    /** The provider runs billing cycles, retries and dunning itself (Stripe). */
    MANAGED_SUBSCRIPTIONS,
    /** We charge a stored card token on our own schedule (WayForPay). */
    MERCHANT_RECURRING,
    CUSTOMER_PORTAL,
    EXTERNAL_INVOICES,
    CUSTOMERS
}
