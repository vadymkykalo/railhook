package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.InvoiceResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Provider-agnostic billing orchestrator.
 * Routes operations to the correct provider via {@link BillingProviderRegistry}.
 * Subscription lifecycle is managed by {@link SubscriptionLifecycleService}.
 */
@Service
@Slf4j
public class BillingService {

    private static final List<PaymentStatus> SETTLED_BY_SUCCESS = List.of(
            PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED, PaymentStatus.PARTIALLY_REFUNDED);
    private static final List<PaymentStatus> SETTLED_BY_FAILURE = List.of(
            PaymentStatus.FAILED, PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED, PaymentStatus.PARTIALLY_REFUNDED);

    private final boolean billingEnabled;
    private final BillingProviderRegistry providerRegistry;
    private final PlanRepository planRepository;
    private final OrganizationRepository organizationRepository;
    private final BillingSubscriptionRepository subscriptionRepository;
    private final BillingInvoiceRepository invoiceRepository;
    private final BillingPaymentRepository paymentRepository;
    private final EntitlementService entitlementService;
    private final SubscriptionLifecycleService lifecycleService;

    public BillingService(
            @Value("${billing.enabled:false}") boolean billingEnabled,
            BillingProviderRegistry providerRegistry,
            PlanRepository planRepository,
            OrganizationRepository organizationRepository,
            BillingSubscriptionRepository subscriptionRepository,
            BillingInvoiceRepository invoiceRepository,
            BillingPaymentRepository paymentRepository,
            EntitlementService entitlementService,
            SubscriptionLifecycleService lifecycleService) {
        this.billingEnabled = billingEnabled;
        this.providerRegistry = providerRegistry;
        this.planRepository = planRepository;
        this.organizationRepository = organizationRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.entitlementService = entitlementService;
        this.lifecycleService = lifecycleService;
        log.info("BillingService initialized: enabled={}, default provider={}",
                billingEnabled, providerRegistry.getDefault().getProviderCode());
    }

    /**
     * Self-hosted installs have no billing provider, so every organization is moved onto the
     * {@code self_hosted} plan once the context is up.
     *
     * <p>Startup work has no request and therefore no tenant, and the resolver is asked on every
     * session Hibernate opens — not only for {@code @TenantId} entities. Without this the listener
     * races {@code TenancyConfig.endStartupTenantGrace} for the same event and, whenever it loses,
     * fails the whole startup with {@code TenantNotResolvedException}.
     */
    @SystemTenant("startup work that reassigns plans across every organization, before any request exists")
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureCorrectPlansOnStartup() {
        if (billingEnabled) return;
        planRepository.findByName("self_hosted").ifPresent(selfHostedPlan -> {
            int upgraded = organizationRepository.bulkAssignPlan(selfHostedPlan);
            if (upgraded > 0) {
                log.info("Self-hosted mode: upgraded {} organizations to 'self_hosted' plan", upgraded);
            }
        });
    }

    public boolean isBillingEnabled() { return billingEnabled; }

    public String getDefaultProviderCode() { return providerRegistry.getDefault().getProviderCode(); }

    // ── Plan catalog ────────────────────────────────────────────────

    public List<Plan> listActivePlans() {
        return planRepository.findByActiveTrueOrderByPriceMonthlyCentsAsc();
    }

    public Plan getPlanByName(String name) {
        return planRepository.findByName(name)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + name));
    }

    // ── Organization billing ────────────────────────────────────────

    public Plan getOrganizationPlan() {
        return entitlementService.getPlan();
    }

    @Transactional
    /**
     * Assigns a plan directly, without a payment.
     *
     * <p>This is the downgrade path, and it used to be the upgrade path too: the endpoint
     * behind it is `PUT /api/v1/billing/organization/plan`, guarded by nothing but the OWNER
     * role, and it set whatever plan name it was given. Its own OpenAPI description said "for
     * paid plans, use checkout instead" — a sentence, not a check. Any customer could
     * {@code PUT {"planName":"pro"}} and be on Pro, or ask for {@code self_hosted}, which is
     * seeded active and unlimited on everything.
     *
     * <p>So the rule is a whitelist rather than a price test: with billing on, an
     * organization can move itself to a plan it is already paying for, or to a zero-priced
     * plan that is actually offered for self-service — which is `free` and nothing else.
     * Everything else goes through checkout. With billing off there is no money involved and
     * no reason to refuse.
     */
    public void assignPlan(String planName) {
        UUID organizationId = TenantContext.require();
        Organization org = findOrg();
        Plan plan = getPlanByName(planName);
        requireSelfAssignable(plan, organizationId);
        org.setPlan(plan);
        organizationRepository.save(org);
        entitlementService.evictPlanCache(organizationId);
        log.info("Plan assigned: org={} plan={}", organizationId, planName);
    }

    /** The only plan an organization may put itself on without paying for it. */
    private static final String SELF_SERVICE_PLAN = "free";

    /** The provider code of a deployment that takes no payments. */
    public static final String NO_PAYMENT_PROVIDER = "noop";

    private void requireSelfAssignable(Plan plan, UUID organizationId) {
        if (!billingEnabled) {
            return;
        }
        if (SELF_SERVICE_PLAN.equals(plan.getName())) {
            return;
        }
        boolean alreadyPaidFor = subscriptionRepository.findActiveByOrganizationId(organizationId)
                .map(BillingSubscription::getPlan)
                .filter(current -> current.getId().equals(plan.getId()))
                .isPresent();
        if (!alreadyPaidFor) {
            throw new ForbiddenException(
                    "Plan '" + plan.getName() + "' cannot be assigned directly. Start a checkout session "
                            + "for it, or switch to '" + SELF_SERVICE_PLAN + "'.");
        }
    }

    // ── Checkout (create payment page) ──────────────────────────────

    /**
     * Starts a paid checkout and returns the provider's payment page.
     *
     * <p>The subscription is created here, {@code PENDING}, before the customer pays: the
     * organization and plan come from the request, and the row is stored under whatever the
     * provider's payment callback will carry — WayForPay's signed orderReference, or for Stripe the
     * customer its first invoice names. The first successful payment finds this row and activates
     * it ({@link #processWebhook}). Nothing used to create one, so a paid checkout found no
     * subscription, logged a warning, and left the organization on Free.
     *
     * <p>The organization row lock serialises two checkouts started together; a newer checkout
     * expires the pending one, and a checkout is refused while a subscription is live — a second
     * would be charged beside the first.
     */
    @Transactional
    public String createCheckoutSession(String planName,
                                         String providerCode, String billingInterval,
                                         String successUrl, String cancelUrl) {
        UUID organizationId = TenantContext.require();
        Organization org = findOrg();
        Plan plan = getPlanByName(planName);
        BillingProvider provider = providerRegistry.get(providerCode != null ? providerCode : providerRegistry.getDefault().getProviderCode());
        // The no-op provider's "payment page" is the success URL itself, so a checkout through it
        // would return the customer from a payment they never made.
        if (NO_PAYMENT_PROVIDER.equals(provider.getProviderCode())) {
            throw new ConflictException("Paid plans are not available on this deployment: no payment provider is configured.");
        }
        BillingInterval interval = parseBillingInterval(billingInterval);
        long catalogPriceCents = interval == BillingInterval.YEARLY
                ? plan.getPriceYearlyCents() : plan.getPriceMonthlyCents();
        if (catalogPriceCents <= 0) {
            throw new ConflictException("Plan '" + plan.getName() + "' is not sold through checkout.");
        }

        organizationRepository.lockById(organizationId);
        if (subscriptionRepository.findActiveByOrganizationId(organizationId).isPresent()) {
            throw new ConflictException("This organization already has a subscription. Cancel it before "
                    + "starting a checkout for another plan.");
        }

        // A provider customer outlives its subscriptions, so an earlier one is reused.
        String externalCustomerId = null;
        if (provider.supports(BillingCapability.CUSTOMERS)) {
            externalCustomerId = subscriptionRepository
                    .findFirstByOrganizationIdAndProviderCodeAndExternalCustomerIdIsNotNullOrderByCreatedAtDesc(
                            organizationId, provider.getProviderCode())
                    .map(BillingSubscription::getExternalCustomerId)
                    .orElse(null);
            if (externalCustomerId == null) {
                externalCustomerId = provider.createCustomer(organizationId, org.getName(), org.getBillingEmail());
            }
        }

        long priceCents = provider.checkoutPriceCents(plan.getName(), interval, catalogPriceCents);
        String currency = provider.getDefaultCurrency();

        BillingProvider.CreatePaymentResult result = provider.createPaymentPage(
                new BillingProvider.CreatePaymentRequest(
                        organizationId, externalCustomerId, planName,
                        priceCents, currency,
                        successUrl, cancelUrl,
                        Map.of("organizationId", organizationId.toString(),
                               "billingInterval", interval.name())
                ));

        // A managed provider (Stripe) creates its subscription when the session completes, and
        // its callbacks name that and the customer, never the session. Any other provider's
        // callbacks carry the reference its payment page was created under.
        boolean managed = provider.supports(BillingCapability.MANAGED_SUBSCRIPTIONS);
        lifecycleService.createPending(plan, provider.getProviderCode(), currency, interval, priceCents,
                externalCustomerId,
                managed ? null : result.externalSessionId(),
                managed ? result.externalSessionId() : null);

        log.info("Checkout created: org={} plan={} provider={}", organizationId, planName, provider.getProviderCode());
        return result.redirectUrl();
    }

    // ── Portal session ──────────────────────────────────────────────

    public String createPortalSession(String returnUrl) {
        UUID organizationId = TenantContext.require();
        var sub = subscriptionRepository.findActiveByOrganizationId(organizationId).orElse(null);
        if (sub == null || sub.getExternalCustomerId() == null) return returnUrl;

        BillingProvider provider = providerRegistry.get(sub.getProviderCode());
        if (!provider.supports(BillingCapability.CUSTOMER_PORTAL)) return returnUrl;

        return provider.createPortalSession(sub.getExternalCustomerId(), returnUrl);
    }

    // ── Cancel subscription ─────────────────────────────────────────

    @Transactional
    public void cancelSubscription() {
        UUID organizationId = TenantContext.require();
        var sub = subscriptionRepository.findActiveByOrganizationId(organizationId)
                .orElseThrow(() -> new NotFoundException("No active subscription for this organization"));

        BillingProvider provider = providerRegistry.get(sub.getProviderCode());

        // Cancel in external system if managed
        if (provider.supports(BillingCapability.MANAGED_SUBSCRIPTIONS) && sub.getExternalSubscriptionId() != null) {
            provider.cancelExternalSubscription(sub.getExternalSubscriptionId());
        }

        lifecycleService.cancel(sub.getId(), "User requested cancellation");
        log.info("Subscription cancelled: org={} sub={}", organizationId, sub.getId());
    }

    // ── Invoices ────────────────────────────────────────────────────

    public List<InvoiceResponse> listInvoices() {
        UUID organizationId = TenantContext.require();
        // First, check local invoices
        List<BillingInvoice> localInvoices = invoiceRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
        if (!localInvoices.isEmpty()) {
            return localInvoices.stream()
                    .map(this::toInvoiceResponse)
                    .collect(Collectors.toList());
        }

        // Fallback: fetch from external provider
        var sub = subscriptionRepository.findActiveByOrganizationId(organizationId).orElse(null);
        if (sub == null || sub.getExternalCustomerId() == null) return List.of();

        BillingProvider provider = providerRegistry.get(sub.getProviderCode());
        if (!provider.supports(BillingCapability.EXTERNAL_INVOICES)) return List.of();

        return provider.fetchInvoices(sub.getExternalCustomerId()).stream()
                .map(ext -> InvoiceResponse.builder()
                        .id(ext.externalInvoiceId())
                        .status(ext.status())
                        .amountCents((int) ext.amountCents())
                        .currency(ext.currency())
                        .planName(ext.planName())
                        .periodStart(ext.periodStart())
                        .periodEnd(ext.periodEnd())
                        .paidAt(ext.paidAt())
                        .invoiceUrl(ext.hostedUrl())
                        .build())
                .collect(Collectors.toList());
    }

    // ── Webhook processing (per provider) ───────────────────────────

    @SystemTenant("called by the payment provider, not by a tenant; the subscription it looks up by external id is what identifies the organization")
    @Transactional
    public void processWebhook(String providerCode, String rawPayload, Map<String, String> headers) {
        BillingProvider provider = providerRegistry.get(providerCode);
        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(rawPayload, headers);
        if (event == null) {
            log.warn("Billing webhook: failed to parse or verify signature (provider={})", providerCode);
            return;
        }
        log.info("Billing webhook: provider={} type={}", providerCode, event.eventType());
        handleWebhookEvent(provider, event);
    }

    private void handleWebhookEvent(BillingProvider provider, BillingProvider.BillingWebhookEvent event) {
        // Find subscription by external IDs
        BillingSubscription sub = null;
        if (event.externalSubscriptionId() != null) {
            sub = subscriptionRepository.findByExternalSubscriptionId(event.externalSubscriptionId()).orElse(null);
        }
        if (sub == null && event.externalCustomerId() != null) {
            sub = subscriptionRepository.findFirstByExternalCustomerIdOrderByCreatedAtDesc(event.externalCustomerId())
                    .orElse(null);
        }

        final BillingSubscription subscription = sub;

        switch (event.eventType()) {
            case "invoice.paid", "payment.succeeded" -> {
                if (subscription != null && alreadySettled(provider, subscription, event, SETTLED_BY_SUCCESS)) {
                    log.info("Billing webhook: payment {} already recorded as succeeded, ignoring replay",
                            event.externalPaymentId());
                } else if (subscription != null) {
                    // Stripe's subscription exists only once the checkout completes; its first
                    // invoice finds the pending row by customer, and binds it here.
                    if (subscription.getExternalSubscriptionId() == null && event.externalSubscriptionId() != null) {
                        lifecycleService.setExternalIds(subscription.getId(),
                                subscription.getExternalCustomerId() != null
                                        ? subscription.getExternalCustomerId() : event.externalCustomerId(),
                                event.externalSubscriptionId());
                    }
                    BillingPayment payment = BillingPayment.builder()
                            .organizationId(subscription.getOrganizationId())
                            .subscriptionId(subscription.getId())
                            .providerCode(provider.getProviderCode())
                            .externalPaymentId(event.externalPaymentId())
                            .status(PaymentStatus.SUCCEEDED)
                            .amountCents(event.amountCents() != null ? event.amountCents() : 0)
                            .currency(event.currency() != null ? event.currency() : provider.getDefaultCurrency())
                            .cardLast4(event.cardLast4())
                            .cardBrand(event.cardBrand())
                            .build();
                    paymentRepository.save(payment);

                    if (event.recurringToken() != null) {
                        lifecycleService.setRecurringToken(subscription.getId(), event.recurringToken(),
                                event.cardLast4(), event.cardBrand());
                    }

                    if (isUnpaidCheckout(subscription)) {
                        if (!activateCheckout(subscription, event)) {
                            return;
                        }
                    } else if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
                        // Without periods from the provider, the paid period follows on from the
                        // current one rather than restarting today.
                        Instant periodStart = event.periodStart() != null ? event.periodStart()
                                : subscription.getCurrentPeriodEnd() != null ? subscription.getCurrentPeriodEnd()
                                : Instant.now();
                        Instant periodEnd = event.periodEnd() != null ? event.periodEnd()
                                : periodAfter(periodStart, subscription.getBillingInterval());
                        lifecycleService.renew(subscription.getId(), periodStart, periodEnd);
                    } else {
                        Instant periodStart = event.periodStart() != null ? event.periodStart() : Instant.now();
                        Instant periodEnd = event.periodEnd() != null ? event.periodEnd()
                                : periodAfter(periodStart, subscription.getBillingInterval());
                        lifecycleService.activate(subscription.getId(), periodStart, periodEnd);
                    }

                    if (event.planName() != null) {
                        planRepository.findByName(event.planName()).ifPresent(plan ->
                                lifecycleService.changePlan(subscription.getId(), plan));
                    }
                } else if (event.externalCustomerId() != null) {
                    log.warn("Billing webhook: payment succeeded but no subscription found for customer {}",
                            event.externalCustomerId());
                }
            }
            case "invoice.payment_failed", "payment.failed" -> {
                if (subscription != null && alreadySettled(provider, subscription, event, SETTLED_BY_FAILURE)) {
                    log.info("Billing webhook: payment {} already recorded, ignoring failure callback",
                            event.externalPaymentId());
                } else if (subscription != null) {
                    BillingPayment payment = BillingPayment.builder()
                            .organizationId(subscription.getOrganizationId())
                            .subscriptionId(subscription.getId())
                            .providerCode(provider.getProviderCode())
                            .externalPaymentId(event.externalPaymentId())
                            .status(PaymentStatus.FAILED)
                            .amountCents(event.amountCents() != null ? event.amountCents() : 0)
                            .currency(event.currency() != null ? event.currency() : provider.getDefaultCurrency())
                            .failureCode(event.failureCode())
                            .failureMessage(event.failureMessage())
                            .build();
                    paymentRepository.save(payment);
                    // A declined first payment leaves the checkout open for another attempt.
                    // Nothing was ever paid, so there is nothing past due and no dunning to start.
                    if (!isUnpaidCheckout(subscription)) {
                        lifecycleService.markPastDue(subscription.getId(),
                                "Payment failed: " + event.failureCode());
                    }
                }
            }
            case "customer.subscription.deleted" -> {
                if (subscription != null && isUnpaidCheckout(subscription)) {
                    lifecycleService.abandon(subscription.getId(), "Checkout dropped by provider");
                } else if (subscription != null) {
                    lifecycleService.cancel(subscription.getId(), "Cancelled externally by provider");
                }
            }
            case "customer.subscription.updated" -> {
                if (subscription != null && isUnpaidCheckout(subscription)) {
                    // Only a payment activates a checkout; the update that precedes it does not.
                    log.debug("Billing webhook: subscription update for unpaid checkout {}, ignoring",
                            subscription.getId());
                    return;
                }
                if (subscription != null && event.planName() != null) {
                    planRepository.findByName(event.planName()).ifPresent(plan ->
                            lifecycleService.changePlan(subscription.getId(), plan));
                }
                if (subscription != null && event.periodStart() != null && event.periodEnd() != null) {
                    lifecycleService.activate(subscription.getId(), event.periodStart(), event.periodEnd());
                }
            }
            case "payment.refunded" -> {
                if (event.externalPaymentId() != null) {
                    paymentRepository.findFirstByProviderCodeAndExternalPaymentIdAndStatusInOrderByCreatedAtDesc(
                            provider.getProviderCode(), event.externalPaymentId(), SETTLED_BY_SUCCESS).ifPresent(payment -> {
                        // A refund for less than the charge is a partial one. This used to record
                        // the smaller figure in refundedCents and still stamp the row REFUNDED, so
                        // the status said the customer's money was back and the amount said it was
                        // not; PARTIALLY_REFUNDED existed for exactly this and was never set.
                        long refunded = event.amountCents() != null ? event.amountCents() : payment.getAmountCents();
                        payment.setRefundedCents(refunded);
                        payment.setStatus(refunded < payment.getAmountCents()
                                ? PaymentStatus.PARTIALLY_REFUNDED
                                : PaymentStatus.REFUNDED);
                        paymentRepository.save(payment);
                    });
                }
            }
            default -> log.debug("Unhandled billing webhook event: {}", event.eventType());
        }
    }

    /**
     * A checkout that has not been paid: {@code PENDING}, or expired by a newer checkout before
     * the customer paid this one (it was never activated, so it has no period).
     */
    private static boolean isUnpaidCheckout(BillingSubscription subscription) {
        return subscription.getStatus() == SubscriptionStatus.PENDING
                || (subscription.getStatus() == SubscriptionStatus.EXPIRED && subscription.getCurrentPeriodStart() == null);
    }

    /**
     * The first payment for a checkout: the organization moves to the plan it paid for.
     *
     * <p>A customer can open a second checkout and then pay on the first page anyway. That payment
     * is real, so the checkout it paid for is the one activated and any other open checkout is
     * expired. If the organization already has a live subscription it has paid twice: the payment
     * is recorded (the caller already saved it) for a refund, and no second subscription goes live.
     *
     * @return whether the checkout was activated
     */
    private boolean activateCheckout(BillingSubscription checkout, BillingProvider.BillingWebhookEvent event) {
        UUID organizationId = checkout.getOrganizationId();
        organizationRepository.lockById(organizationId);
        var live = subscriptionRepository.findActiveByOrganizationId(organizationId)
                .filter(other -> !other.getId().equals(checkout.getId()));
        if (live.isPresent()) {
            log.error("Billing webhook: payment {} for checkout {} arrived while subscription {} is live for "
                            + "org {} — the organization has paid twice; refund the payment in the provider",
                    event.externalPaymentId(), checkout.getId(), live.get().getId(), organizationId);
            return false;
        }
        for (BillingSubscription other : subscriptionRepository.findByOrganizationIdAndStatus(
                organizationId, SubscriptionStatus.PENDING)) {
            if (!other.getId().equals(checkout.getId())) {
                lifecycleService.abandon(other.getId(), "An earlier checkout was paid");
            }
        }
        Instant periodStart = event.periodStart() != null ? event.periodStart() : Instant.now();
        Instant periodEnd = event.periodEnd() != null ? event.periodEnd()
                : periodAfter(periodStart, checkout.getBillingInterval());
        lifecycleService.activate(checkout.getId(), periodStart, periodEnd);
        log.info("Checkout paid: sub={} org={} plan={}", checkout.getId(), organizationId,
                checkout.getPlan() != null ? checkout.getPlan().getName() : null);
        return true;
    }

    private static Instant periodAfter(Instant start, BillingInterval interval) {
        BillingInterval effective = interval != null ? interval : BillingInterval.MONTHLY;
        return start.atZone(ZoneOffset.UTC).plus(effective.getPeriod()).toInstant();
    }

    /**
     * A provider callback is signed but carries no nonce, so the same signed "succeeded" callback
     * can be delivered again — by the provider's own retries or by anyone who kept a copy — and each
     * delivery would renew the period once more. The recorded payment is the idempotency record:
     * a callback for a payment already settled in a way that outranks it is a replay. The
     * organization row lock serialises concurrent deliveries of the same callback, so the check
     * and the insert that follows it cannot interleave.
     */
    private boolean alreadySettled(BillingProvider provider, BillingSubscription subscription,
                                   BillingProvider.BillingWebhookEvent event, List<PaymentStatus> settledBy) {
        if (event.externalPaymentId() == null) {
            return false;
        }
        organizationRepository.lockById(subscription.getOrganizationId());
        return paymentRepository.existsByProviderCodeAndExternalPaymentIdAndStatusIn(
                provider.getProviderCode(), event.externalPaymentId(), settledBy);
    }

    // ── Subscription history ────────────────────────────────────────

    public List<BillingSubscription> getSubscriptionHistory() {
        UUID organizationId = TenantContext.require();
        return subscriptionRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }

    public List<BillingPayment> getPaymentHistory() {
        UUID organizationId = TenantContext.require();
        return paymentRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }

    // ── Internal ────────────────────────────────────────────────────

    private BillingInterval parseBillingInterval(String raw) {
        if (raw == null || raw.isBlank()) return BillingInterval.MONTHLY;
        try {
            return BillingInterval.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BillingInterval.MONTHLY;
        }
    }

    private Organization findOrg() {
        UUID organizationId = TenantContext.require();
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
    }

    private InvoiceResponse toInvoiceResponse(BillingInvoice inv) {
        return InvoiceResponse.builder()
                .id(inv.getId().toString())
                .status(inv.getStatus().name())
                .amountCents((int) inv.getTotalCents())
                .currency(inv.getCurrency())
                .periodStart(inv.getPeriodStart())
                .periodEnd(inv.getPeriodEnd())
                .paidAt(inv.getPaidAt())
                .invoiceUrl(inv.getHostedUrl())
                .build();
    }
}
