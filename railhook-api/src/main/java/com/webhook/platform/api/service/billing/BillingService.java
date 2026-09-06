package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.InvoiceResponse;
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

import java.time.Duration;
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

    @Transactional
    public String createCheckoutSession(String planName,
                                         String providerCode, String billingInterval,
                                         String successUrl, String cancelUrl) {
        UUID organizationId = TenantContext.require();
        Organization org = findOrg();
        Plan plan = getPlanByName(planName);
        BillingProvider provider = providerRegistry.get(providerCode != null ? providerCode : providerRegistry.getDefault().getProviderCode());
        BillingInterval interval = parseBillingInterval(billingInterval);

        // For providers that support customers (Stripe), create one if needed
        String externalCustomerId = null;
        if (provider.supports(BillingCapability.CUSTOMERS)) {
            var existingSub = subscriptionRepository.findActiveByOrganizationId(organizationId);
            externalCustomerId = existingSub.map(BillingSubscription::getExternalCustomerId).orElse(null);
            if (externalCustomerId == null) {
                externalCustomerId = provider.createCustomer(organizationId, org.getName(), org.getBillingEmail());
            }
        }

        long priceCents = interval == BillingInterval.YEARLY
                ? plan.getPriceYearlyCents() : plan.getPriceMonthlyCents();

        BillingProvider.CreatePaymentResult result = provider.createPaymentPage(
                new BillingProvider.CreatePaymentRequest(
                        organizationId, externalCustomerId, planName,
                        priceCents, provider.getDefaultCurrency(),
                        successUrl, cancelUrl,
                        Map.of("organizationId", organizationId.toString(),
                               "billingInterval", interval.name())
                ));

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
            sub = subscriptionRepository.findByExternalCustomerId(event.externalCustomerId()).orElse(null);
        }

        final BillingSubscription subscription = sub;

        switch (event.eventType()) {
            case "invoice.paid", "payment.succeeded" -> {
                if (subscription != null) {
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

                    Instant periodStart = event.periodStart() != null ? event.periodStart() : Instant.now();
                    Instant periodEnd = event.periodEnd() != null ? event.periodEnd() : Instant.now().plus(Duration.ofDays(30));
                    if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
                        lifecycleService.renew(subscription.getId(), periodStart, periodEnd);
                    } else {
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
                if (subscription != null) {
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
                    lifecycleService.markPastDue(subscription.getId(),
                            "Payment failed: " + event.failureCode());
                }
            }
            case "customer.subscription.deleted" -> {
                if (subscription != null) {
                    lifecycleService.cancel(subscription.getId(), "Cancelled externally by provider");
                }
            }
            case "customer.subscription.updated" -> {
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
                    paymentRepository.findByExternalPaymentId(event.externalPaymentId()).ifPresent(payment -> {
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
