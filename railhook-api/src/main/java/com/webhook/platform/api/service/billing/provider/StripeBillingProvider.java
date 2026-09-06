package com.webhook.platform.api.service.billing.provider;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.billingportal.Session;
import com.stripe.net.Webhook;
import com.stripe.param.*;
import com.stripe.param.billingportal.SessionCreateParams;
import com.webhook.platform.api.service.billing.BillingCapability;
import com.webhook.platform.api.service.billing.BillingProvider;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Stripe billing provider.
 * Stripe manages subscription lifecycle (billing cycles, automatic retries, dunning).
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code STRIPE_SECRET_KEY}</li>
 *   <li>{@code STRIPE_WEBHOOK_SECRET}</li>
 *   <li>{@code STRIPE_PRICE_MAP} — plan name → Stripe Price ID (e.g. starter=price_xxx,pro=price_yyy)</li>
 * </ul>
 */
@Slf4j
public class StripeBillingProvider implements BillingProvider {

    private static final Set<BillingCapability> CAPABILITIES = Set.of(
            BillingCapability.MANAGED_SUBSCRIPTIONS,
            BillingCapability.CUSTOMERS,
            BillingCapability.CUSTOMER_PORTAL,
            BillingCapability.EXTERNAL_INVOICES
    );

    private final String webhookSecret;
    private final Map<String, String> planToPriceId;

    public StripeBillingProvider(String secretKey, String webhookSecret, Map<String, String> planToPriceId) {
        Stripe.apiKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.planToPriceId = planToPriceId;
        log.info("Stripe billing provider initialized with {} plan mappings", planToPriceId.size());
    }

    @Override
    public String getProviderCode() { return "stripe"; }

    @Override
    public String getDisplayName() { return "Stripe"; }

    @Override
    public Set<BillingCapability> capabilities() { return CAPABILITIES; }

    // ── Customers ───────────────────────────────────────────────────

    @Override
    public String createCustomer(UUID organizationId, String name, String email) {
        try {
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setName(name)
                    .setEmail(email)
                    .putMetadata("organizationId", organizationId.toString())
                    .build();
            Customer customer = Customer.create(params);
            log.info("Stripe: created customer {} for org {}", customer.getId(), organizationId);
            return customer.getId();
        } catch (StripeException e) {
            log.error("Stripe: failed to create customer for org {}", organizationId, e);
            throw new RuntimeException("Stripe customer creation failed: " + e.getMessage(), e);
        }
    }

    // ── Payment page (Checkout Session) ─────────────────────────────

    @Override
    public CreatePaymentResult createPaymentPage(CreatePaymentRequest request) {
        String priceId = planToPriceId.get(request.planName());
        if (priceId == null) {
            throw new IllegalArgumentException("No Stripe Price ID configured for plan: " + request.planName());
        }

        try {
            var builder = com.stripe.param.checkout.SessionCreateParams.builder()
                    .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                    .setSuccessUrl(request.successUrl())
                    .setCancelUrl(request.cancelUrl())
                    .addLineItem(
                            com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                                    .setPrice(priceId)
                                    .setQuantity(1L)
                                    .build()
                    );

            if (request.externalCustomerId() != null) {
                builder.setCustomer(request.externalCustomerId());
            }
            if (request.metadata() != null) {
                request.metadata().forEach(builder::putMetadata);
            }

            com.stripe.model.checkout.Session session = com.stripe.model.checkout.Session.create(builder.build());
            log.info("Stripe: created checkout session {} for plan {}", session.getId(), request.planName());
            return new CreatePaymentResult(session.getUrl(), session.getId());
        } catch (StripeException e) {
            log.error("Stripe: failed to create checkout session for plan {}", request.planName(), e);
            throw new RuntimeException("Stripe checkout creation failed: " + e.getMessage(), e);
        }
    }

    // ── Managed subscriptions ───────────────────────────────────────

    @Override
    public String createSubscription(String externalCustomerId, String planExternalId, String currency) {
        String priceId = planToPriceId.getOrDefault(planExternalId, planExternalId);
        try {
            SubscriptionCreateParams params = SubscriptionCreateParams.builder()
                    .setCustomer(externalCustomerId)
                    .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
                    .build();
            Subscription sub = Subscription.create(params);
            log.info("Stripe: created subscription {} for customer {}", sub.getId(), externalCustomerId);
            return sub.getId();
        } catch (StripeException e) {
            log.error("Stripe: failed to create subscription for customer {}", externalCustomerId, e);
            throw new RuntimeException("Stripe subscription creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelExternalSubscription(String externalSubscriptionId) {
        try {
            Subscription sub = Subscription.retrieve(externalSubscriptionId);
            sub.cancel();
            log.info("Stripe: cancelled subscription {}", externalSubscriptionId);
        } catch (StripeException e) {
            log.error("Stripe: failed to cancel subscription {}", externalSubscriptionId, e);
            throw new RuntimeException("Stripe subscription cancellation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String createPortalSession(String externalCustomerId, String returnUrl) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setCustomer(externalCustomerId)
                    .setReturnUrl(returnUrl)
                    .build();
            Session session = Session.create(params);
            log.info("Stripe: created portal session for customer {}", externalCustomerId);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Stripe: failed to create portal session for customer {}", externalCustomerId, e);
            throw new RuntimeException("Stripe portal session creation failed: " + e.getMessage(), e);
        }
    }

    // ── Invoices ────────────────────────────────────────────────────

    @Override
    public List<ExternalInvoice> fetchInvoices(String externalCustomerId) {
        try {
            InvoiceListParams params = InvoiceListParams.builder()
                    .setCustomer(externalCustomerId)
                    .setLimit(20L)
                    .build();
            InvoiceCollection invoices = Invoice.list(params);
            return invoices.getData().stream()
                    .map(inv -> new ExternalInvoice(
                            inv.getId(),
                            inv.getStatus(),
                            inv.getAmountDue() != null ? inv.getAmountDue() : 0L,
                            inv.getCurrency() != null ? inv.getCurrency().toUpperCase() : getDefaultCurrency(),
                            null,
                            inv.getPeriodStart() != null ? Instant.ofEpochSecond(inv.getPeriodStart()) : null,
                            inv.getPeriodEnd() != null ? Instant.ofEpochSecond(inv.getPeriodEnd()) : null,
                            inv.getStatusTransitions() != null && inv.getStatusTransitions().getPaidAt() != null
                                    ? Instant.ofEpochSecond(inv.getStatusTransitions().getPaidAt()) : null,
                            inv.getHostedInvoiceUrl(),
                            inv.getInvoicePdf()
                    ))
                    .collect(Collectors.toList());
        } catch (StripeException e) {
            log.error("Stripe: failed to fetch invoices for customer {}", externalCustomerId, e);
            return List.of();
        }
    }

    // ── Reconciliation ─────────────────────────────────────────────

    @Override
    public ExternalSubscriptionState fetchSubscriptionStatus(String externalSubscriptionId) {
        try {
            Subscription sub = Subscription.retrieve(externalSubscriptionId);
            String priceId = (sub.getItems() != null && !sub.getItems().getData().isEmpty())
                    ? sub.getItems().getData().get(0).getPrice().getId()
                    : null;
            String planName = planToPriceId.entrySet().stream()
                    .filter(e -> e.getValue().equals(priceId))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);

            Instant periodStart = sub.getCurrentPeriodStart() != null
                    ? Instant.ofEpochSecond(sub.getCurrentPeriodStart()) : null;
            Instant periodEnd = sub.getCurrentPeriodEnd() != null
                    ? Instant.ofEpochSecond(sub.getCurrentPeriodEnd()) : null;

            return new ExternalSubscriptionState(
                    sub.getId(),
                    sub.getStatus(),
                    planName,
                    periodStart,
                    periodEnd,
                    Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())
            );
        } catch (StripeException e) {
            log.error("Stripe: failed to fetch subscription {}: {}", externalSubscriptionId, e.getMessage());
            return null;
        }
    }

    // ── Webhooks ────────────────────────────────────────────────────

    @Override
    public BillingWebhookEvent parseWebhook(String rawPayload, Map<String, String> headers) {
        String signature = headers.getOrDefault("stripe-signature",
                headers.getOrDefault("Stripe-Signature", ""));

        Event event;
        try {
            event = Webhook.constructEvent(rawPayload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe: invalid webhook signature");
            return null;
        }

        String eventType = event.getType();
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isEmpty()) {
            log.warn("Stripe: could not deserialize webhook event data for type {}", eventType);
            return null;
        }

        StripeObject stripeObject = deserializer.getObject().get();

        return switch (eventType) {
            case "invoice.paid", "invoice.payment_failed" -> mapInvoiceEvent(eventType, (Invoice) stripeObject);
            case "customer.subscription.deleted",
                 "customer.subscription.updated" -> mapSubscriptionEvent(eventType, (Subscription) stripeObject);
            default -> {
                log.debug("Stripe: ignoring event type {}", eventType);
                yield null;
            }
        };
    }

    private BillingWebhookEvent mapInvoiceEvent(String eventType, Invoice invoice) {
        String customerId = invoice.getCustomer();
        String subscriptionId = invoice.getSubscription();
        Instant periodStart = invoice.getPeriodStart() != null ? Instant.ofEpochSecond(invoice.getPeriodStart()) : null;
        Instant periodEnd = invoice.getPeriodEnd() != null ? Instant.ofEpochSecond(invoice.getPeriodEnd()) : null;

        return new BillingWebhookEvent(
                eventType, customerId, subscriptionId, invoice.getPaymentIntent(),
                null, invoice.getAmountDue(), invoice.getCurrency(),
                null, null, null, null, null,
                periodStart, periodEnd, Map.of()
        );
    }

    private BillingWebhookEvent mapSubscriptionEvent(String eventType, Subscription sub) {
        final String priceId = (sub.getItems() != null && !sub.getItems().getData().isEmpty())
                ? sub.getItems().getData().get(0).getPrice().getId()
                : null;
        String planName = planToPriceId.entrySet().stream()
                .filter(e -> e.getValue().equals(priceId))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);

        Instant periodStart = sub.getCurrentPeriodStart() != null ? Instant.ofEpochSecond(sub.getCurrentPeriodStart()) : null;
        Instant periodEnd = sub.getCurrentPeriodEnd() != null ? Instant.ofEpochSecond(sub.getCurrentPeriodEnd()) : null;

        return new BillingWebhookEvent(
                eventType, sub.getCustomer(), sub.getId(), null,
                planName, null, sub.getCurrency(),
                null, null, null, null, null,
                periodStart, periodEnd, Map.of()
        );
    }
}
