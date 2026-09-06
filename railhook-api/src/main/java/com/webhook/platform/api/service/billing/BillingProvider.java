package com.webhook.platform.api.service.billing;

import java.time.Instant;
import java.util.*;

/**
 * Provider-agnostic billing adapter. Each provider declares its {@link BillingCapability capabilities}
 * so that {@link BillingService} knows what to delegate vs. handle internally.
 *
 * <ul>
 *   <li><b>Stripe</b>  — MANAGED_SUBSCRIPTIONS, CUSTOMERS, CUSTOMER_PORTAL, EXTERNAL_INVOICES</li>
 *   <li><b>WayForPay</b> — MERCHANT_RECURRING</li>
 *   <li><b>NoOp</b>     — (none)</li>
 * </ul>
 */
public interface BillingProvider {

    /** Unique code stored in DB columns (e.g. "stripe", "wayforpay", "noop"). */
    String getProviderCode();

    /** Human-readable name for UI. */
    String getDisplayName();

    /** What this provider can do. */
    Set<BillingCapability> capabilities();

    /** Default currency code for this provider (e.g. "USD", "UAH"). */
    default String getDefaultCurrency() { return "USD"; }

    default boolean supports(BillingCapability capability) {
        return capabilities().contains(capability);
    }

    // ── Core: payment page (every paid provider has this) ───────────

    /** Create a hosted payment / checkout page. Returns URL to redirect user. */
    default CreatePaymentResult createPaymentPage(CreatePaymentRequest request) {
        return new CreatePaymentResult(request.successUrl(), null);
    }

    // ── Customers (Stripe) ──────────────────────────────────────────

    /** Create a customer entity in provider. Returns external customer ID. */
    default String createCustomer(UUID organizationId, String name, String email) { return null; }

    // ── Managed subscriptions (Stripe) ──────────────────────────────

    /** Create a subscription in provider. Returns external subscription ID. */
    default String createSubscription(String externalCustomerId, String planExternalId, String currency) { return null; }

    /** Cancel a subscription in provider. */
    default void cancelExternalSubscription(String externalSubscriptionId) {}

    /** Create a self-service billing portal URL. */
    default String createPortalSession(String externalCustomerId, String returnUrl) { return returnUrl; }

    // ── Merchant-initiated recurring (WayForPay) ────────────────────

    /** Charge a stored card/token. Returns payment result. */
    default ChargeResult chargeRecurring(RecurringChargeRequest request) {
        throw new UnsupportedOperationException(getProviderCode() + " does not support merchant-initiated recurring");
    }

    // ── Invoices ────────────────────────────────────────────────────

    /** List invoices from external system. */
    default List<ExternalInvoice> fetchInvoices(String externalCustomerId) { return List.of(); }

    // Refunds are not initiated from here. Railhook has no refund endpoint and no screen that
    // asks for one; a refund is issued in the provider's own dashboard and arrives back as a
    // `payment.refunded` webhook, which BillingService records on the payment row. The outbound
    // refund() this interface used to declare — implemented against the Stripe and WayForPay
    // APIs, and guarded by a REFUNDS capability nothing ever queried — was never called by
    // anything, so it was two untested money-moving code paths one wrong wire away from running.

    // ── Usage reporting (metered billing) ───────────────────────────

    default void reportUsage(String externalSubscriptionId, String metricName, long quantity) {}

    // ── Reconciliation (MANAGED_SUBSCRIPTIONS only) ────────────────

    /**
     * Fetch current subscription state from external provider.
     * Only meaningful for providers with {@link BillingCapability#MANAGED_SUBSCRIPTIONS}.
     * Returns null if the subscription cannot be found or the provider doesn't support this.
     */
    default ExternalSubscriptionState fetchSubscriptionStatus(String externalSubscriptionId) { return null; }

    // ── Webhooks ────────────────────────────────────────────────────

    /** Parse and verify incoming webhook. Returns null if signature is invalid. */
    BillingWebhookEvent parseWebhook(String rawPayload, Map<String, String> headers);

    // ── Inner DTOs (records) ────────────────────────────────────────

    record CreatePaymentRequest(
            UUID organizationId,
            String externalCustomerId,
            String planName,
            long amountCents,
            String currency,
            String successUrl,
            String cancelUrl,
            Map<String, String> metadata
    ) {}

    record CreatePaymentResult(
            String redirectUrl,
            String externalSessionId
    ) {}

    record RecurringChargeRequest(
            UUID organizationId,
            String recurringToken,
            long amountCents,
            String currency,
            String orderReference,
            String description
    ) {}

    record ChargeResult(
            boolean success,
            String externalPaymentId,
            String cardLast4,
            String cardBrand,
            String failureCode,
            String failureMessage
    ) {}

    record ExternalInvoice(
            String externalInvoiceId,
            String status,
            long amountCents,
            String currency,
            String planName,
            Instant periodStart,
            Instant periodEnd,
            Instant paidAt,
            String hostedUrl,
            String pdfUrl
    ) {}

    record ExternalSubscriptionState(
            String externalSubscriptionId,
            String status,
            String planName,
            Instant periodStart,
            Instant periodEnd,
            boolean cancelAtPeriodEnd
    ) {}

    record BillingWebhookEvent(
            String eventType,
            String externalCustomerId,
            String externalSubscriptionId,
            String externalPaymentId,
            String planName,
            Long amountCents,
            String currency,
            String cardLast4,
            String cardBrand,
            String failureCode,
            String failureMessage,
            String recurringToken,
            Instant periodStart,
            Instant periodEnd,
            Map<String, Object> rawData
    ) {}
}
