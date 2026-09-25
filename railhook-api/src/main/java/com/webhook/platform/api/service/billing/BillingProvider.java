package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.BillingInterval;

import java.time.Instant;
import java.util.*;

/** {@link BillingService} delegates only what a provider's {@link BillingCapability capabilities} declare. */
public interface BillingProvider {

    /** Stored in DB columns, e.g. "stripe", "wayforpay", "noop". */
    String getProviderCode();

    String getDisplayName();

    Set<BillingCapability> capabilities();

    default String getDefaultCurrency() { return "USD"; }

    default boolean supports(BillingCapability capability) {
        return capabilities().contains(capability);
    }

    /** In the minor unit of {@link #getDefaultCurrency()}; throws for a plan the provider has no price for. */
    default long checkoutPriceCents(String planName, BillingInterval interval, long catalogPriceCents) {
        return catalogPriceCents;
    }

    default CreatePaymentResult createPaymentPage(CreatePaymentRequest request) {
        return new CreatePaymentResult(request.successUrl(), null);
    }

    default String createCustomer(UUID organizationId, String name, String email) { return null; }

    default String createSubscription(String externalCustomerId, String planExternalId, String currency) { return null; }

    default void cancelExternalSubscription(String externalSubscriptionId) {}

    default String createPortalSession(String externalCustomerId, String returnUrl) { return returnUrl; }

    default ChargeResult chargeRecurring(RecurringChargeRequest request) {
        throw new UnsupportedOperationException(getProviderCode() + " does not support merchant-initiated recurring");
    }

    default List<ExternalInvoice> fetchInvoices(String externalCustomerId) { return List.of(); }

    // No outbound refund: refunds are issued in the provider's dashboard and arrive as a webhook.

    default void reportUsage(String externalSubscriptionId, String metricName, long quantity) {}

    /** Only for {@link BillingCapability#MANAGED_SUBSCRIPTIONS}. Null when not found or unsupported. */
    default ExternalSubscriptionState fetchSubscriptionStatus(String externalSubscriptionId) { return null; }

    /** Returns null if the signature is invalid. */
    BillingWebhookEvent parseWebhook(String rawPayload, Map<String, String> headers);

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
