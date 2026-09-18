package com.webhook.platform.api.service.billing;

import java.util.UUID;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.InvoiceResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingServiceTest {

    @Mock private PlanRepository planRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private BillingSubscriptionRepository subscriptionRepository;
    @Mock private BillingInvoiceRepository invoiceRepository;
    @Mock private BillingPaymentRepository paymentRepository;
    @Mock private EntitlementService entitlementService;
    @Mock private SubscriptionLifecycleService lifecycleService;

    private BillingProviderRegistry providerRegistry;
    private TestBillingProvider stripeProvider;
    private BillingService service;

    private Plan freePlan;
    private Plan starterPlan;
    private Plan proPlan;
    private Organization org;
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID SUB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        starterPlan = Plan.builder().id(UUID.randomUUID()).name("starter").displayName("Starter")
                .priceMonthlyCents(2900).priceYearlyCents(29000).build();
        proPlan = Plan.builder().id(UUID.randomUUID()).name("pro").displayName("Pro")
                .priceMonthlyCents(9900).priceYearlyCents(99000).build();
        freePlan = Plan.builder().id(UUID.randomUUID()).name("free").displayName("Free")
                .priceMonthlyCents(0).build();

        org = Organization.builder().id(ORG_ID).name("Test Org").billingEmail("test@example.com").build();

        stripeProvider = new TestBillingProvider("stripe", "Stripe",
                EnumSet.of(BillingCapability.MANAGED_SUBSCRIPTIONS, BillingCapability.CUSTOMERS,
                        BillingCapability.CUSTOMER_PORTAL, BillingCapability.EXTERNAL_INVOICES));

        var noopProvider = new NoOpBillingProvider();
        providerRegistry = new BillingProviderRegistry(List.of(stripeProvider, noopProvider), "stripe");

        service = new BillingService(
                true, providerRegistry, planRepository, organizationRepository,
                subscriptionRepository, invoiceRepository, paymentRepository,
                entitlementService, lifecycleService);
    }

    // ── Plan catalog ────────────────────────────────────────────────


    /**
     * Every service under test now reads its organization from the ambient tenant scope instead
     * of taking it as a parameter. A unit test has no request to establish one, so it
     * enters the scope itself; without this the first call fails with TenantNotResolvedException.
     */
    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(ORG_ID);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void listActivePlans_delegatesToRepo() {
        when(planRepository.findByActiveTrueOrderByPriceMonthlyCentsAsc())
                .thenReturn(List.of(starterPlan, proPlan));
        assertThat(service.listActivePlans()).containsExactly(starterPlan, proPlan);
    }

    @Test
    void getPlanByName_throwsWhenNotFound() {
        when(planRepository.findByName("gold")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPlanByName("gold"))
                .isInstanceOf(NotFoundException.class);
    }

    // ── assignPlan ──────────────────────────────────────────────────

    /*
     * `PUT /api/v1/billing/organization/plan` is guarded by nothing but the OWNER role, and
     * assignPlan used to set whatever plan name it was handed. Its own OpenAPI description
     * said "for paid plans, use checkout instead" — a sentence, not a check — so any customer
     * could PUT {"planName":"pro"} and be on Pro without paying. The test that stood here
     * asserted exactly that behaviour, which is how it survived.
     */

    @Test
    void assignPlan_allowsTheFreePlan() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("free")).thenReturn(Optional.of(freePlan));

        service.assignPlan("free");

        assertThat(org.getPlan()).isEqualTo(freePlan);
        verify(organizationRepository).save(org);
        verify(entitlementService).evictPlanCache(any());
    }

    @Test
    void assignPlan_refusesAPaidPlanWithNoSubscriptionBehindIt() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignPlan("pro"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("checkout");

        assertThat(org.getPlan()).isNull();
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void assignPlan_refusesTheSelfHostedPlan() {
        Plan selfHosted = Plan.builder().id(UUID.randomUUID()).name("self_hosted")
                .displayName("Self-Hosted").priceMonthlyCents(0).build();
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("self_hosted")).thenReturn(Optional.of(selfHosted));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        /* Zero-priced, seeded active, and unlimited on every quota. A price test would have
           waved it through — which is why the rule is a whitelist of one name. */
        assertThatThrownBy(() -> service.assignPlan("self_hosted"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void assignPlan_allowsAPlanTheOrganizationIsAlreadyPayingFor() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID))
                .thenReturn(Optional.of(BillingSubscription.builder().id(SUB_ID).plan(proPlan).build()));

        // Re-applying the plan behind a live subscription is a repair, not a purchase.
        service.assignPlan("pro");

        assertThat(org.getPlan()).isEqualTo(proPlan);
    }

    @Test
    void assignPlan_isUnrestrictedWhenBillingIsOff() {
        BillingService selfHosted = new BillingService(
                false, providerRegistry, planRepository, organizationRepository,
                subscriptionRepository, invoiceRepository, paymentRepository,
                entitlementService, lifecycleService);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));

        // No money is involved in a self-hosted deployment, so there is nothing to protect.
        selfHosted.assignPlan("pro");

        assertThat(org.getPlan()).isEqualTo(proPlan);
    }

    // ── createCheckoutSession ───────────────────────────────────────

    @Test
    void createCheckoutSession_createsPaymentPage() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("starter")).thenReturn(Optional.of(starterPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        stripeProvider.setCreateCustomerResult("cus_new_123");
        stripeProvider.setCreatePaymentResult(
                new BillingProvider.CreatePaymentResult("https://checkout.stripe.com/session_1", "cs_1"));

        String url = service.createCheckoutSession( "starter", "stripe", "MONTHLY",
                "https://app.com/success", "https://app.com/cancel");

        assertThat(url).isEqualTo("https://checkout.stripe.com/session_1");
        assertThat(stripeProvider.lastPaymentRequest).isNotNull();
        assertThat(stripeProvider.lastPaymentRequest.amountCents()).isEqualTo(2900L);
    }

    @Test
    void createCheckoutSession_reusesTheCustomerOfAnEarlierSubscription() {
        BillingSubscription cancelled = BillingSubscription.builder()
                .id(SUB_ID).status(SubscriptionStatus.CANCELLED).externalCustomerId("cus_existing").build();

        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("starter")).thenReturn(Optional.of(starterPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findFirstByOrganizationIdAndProviderCodeAndExternalCustomerIdIsNotNullOrderByCreatedAtDesc(
                ORG_ID, "stripe")).thenReturn(Optional.of(cancelled));

        stripeProvider.setCreatePaymentResult(
                new BillingProvider.CreatePaymentResult("https://checkout.stripe.com/s2", "cs_2"));

        service.createCheckoutSession( "starter", null, null, "ok", "cancel");

        // Should not create new customer
        assertThat(stripeProvider.createCustomerCalled).isFalse();
        assertThat(stripeProvider.lastPaymentRequest.externalCustomerId()).isEqualTo("cus_existing");
    }

    // A paid checkout used to create nothing: no production code wrote a billing_subscriptions
    // row, so the payment callback found no subscription, logged a warning, and the organization
    // stayed on Free while the provider kept the money.

    @Test
    void createCheckoutSession_opensAPendingSubscriptionBoundToTheReferenceTheProviderEchoes() {
        TestBillingProvider wayforpay = merchantRecurringProvider();
        BillingService withWayForPay = serviceWith(wayforpay);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("starter")).thenReturn(Optional.of(starterPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        wayforpay.setCreatePaymentResult(new BillingProvider.CreatePaymentResult(
                "https://secure.wayforpay.com/page?vkh=1", "railhook_" + ORG_ID + "_1"));

        String url = withWayForPay.createCheckoutSession("starter", "wayforpay", "MONTHLY", "ok", "cancel");

        assertThat(url).isEqualTo("https://secure.wayforpay.com/page?vkh=1");
        // The provider's own price, in its own currency — not the catalog's USD cents.
        assertThat(wayforpay.lastPaymentRequest.amountCents()).isEqualTo(29900L);
        assertThat(wayforpay.lastPaymentRequest.currency()).isEqualTo("UAH");
        verify(organizationRepository).lockById(ORG_ID);
        verify(lifecycleService).createPending(starterPlan, "wayforpay", "UAH", BillingInterval.MONTHLY,
                29900L, null, "railhook_" + ORG_ID + "_1", null);
    }

    @Test
    void createCheckoutSession_bindsAManagedProviderByCustomerAndKeepsTheSession() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        stripeProvider.setCreateCustomerResult("cus_new");
        stripeProvider.setCreatePaymentResult(
                new BillingProvider.CreatePaymentResult("https://checkout.stripe.com/c", "cs_1"));

        service.createCheckoutSession("pro", "stripe", "YEARLY", "ok", "cancel");

        // Stripe creates the subscription when the session completes; its first invoice names
        // the customer, which is what finds this row.
        verify(lifecycleService).createPending(proPlan, "stripe", "USD", BillingInterval.YEARLY,
                99000L, "cus_new", null, "cs_1");
    }

    @Test
    void createCheckoutSession_refusesWhileASubscriptionIsLive() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.of(
                BillingSubscription.builder().id(SUB_ID).plan(starterPlan).status(SubscriptionStatus.ACTIVE).build()));

        // A second checkout would start a second paid subscription beside the first, and both
        // would be charged.
        assertThatThrownBy(() -> service.createCheckoutSession("pro", "stripe", "MONTHLY", "ok", "cancel"))
                .isInstanceOf(ConflictException.class);
        assertThat(stripeProvider.lastPaymentRequest).isNull();
        verify(lifecycleService, never()).createPending(any(), any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void createCheckoutSession_refusesAPlanThatCostsNothing() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("free")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createCheckoutSession("free", "stripe", "MONTHLY", "ok", "cancel"))
                .isInstanceOf(ConflictException.class);
        assertThat(stripeProvider.lastPaymentRequest).isNull();
    }

    @Test
    void createCheckoutSession_usesYearlyPrice() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        stripeProvider.setCreateCustomerResult("cus_y");
        stripeProvider.setCreatePaymentResult(
                new BillingProvider.CreatePaymentResult("https://url", null));

        service.createCheckoutSession( "pro", "stripe", "YEARLY", "ok", "cancel");

        assertThat(stripeProvider.lastPaymentRequest.amountCents()).isEqualTo(99000L);
    }

    @Test
    void createCheckoutSession_refusesWhenNothingCanTakeThePayment() {
        // The no-op provider's payment page is the success URL itself: a checkout through it
        // would send the customer "back from paying" having paid nothing. With no provider
        // configured the deployment offers the free plan only, so the attempt is refused.
        BillingService freeOnly = new BillingService(
                true, new BillingProviderRegistry(List.of(new NoOpBillingProvider()), "noop"),
                planRepository, organizationRepository, subscriptionRepository, invoiceRepository,
                paymentRepository, entitlementService, lifecycleService);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));

        assertThatThrownBy(() -> freeOnly.createCheckoutSession("pro", null, "MONTHLY", "ok", "cancel"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Paid plans");
        // Naming the no-op provider explicitly on a deployment that has a real one is the same.
        assertThatThrownBy(() -> service.createCheckoutSession("pro", "noop", "MONTHLY", "ok", "cancel"))
                .isInstanceOf(ConflictException.class);
    }

    // ── cancelSubscription ──────────────────────────────────────────

    @Test
    void cancelSubscription_cancelsExternalAndLocal() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID))
                .thenReturn(Optional.of(sub));

        service.cancelSubscription();

        assertThat(stripeProvider.cancelledSubscriptionId).isEqualTo("sub_ext_1");
        verify(lifecycleService).cancel(SUB_ID, "User requested cancellation");
    }

    @Test
    void cancelSubscription_throwsWhenNoActiveSub() {
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelSubscription())
                .isInstanceOf(NotFoundException.class);
    }

    // ── createPortalSession ─────────────────────────────────────────

    @Test
    void createPortalSession_returnsPortalUrl() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).providerCode("stripe")
                .externalCustomerId("cus_portal").build();
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID))
                .thenReturn(Optional.of(sub));

        stripeProvider.setPortalUrl("https://billing.stripe.com/portal_1");

        String url = service.createPortalSession( "https://app.com/billing");
        assertThat(url).isEqualTo("https://billing.stripe.com/portal_1");
    }

    @Test
    void createPortalSession_returnsReturnUrlWhenNoSub() {
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID))
                .thenReturn(Optional.empty());
        String url = service.createPortalSession( "https://app.com/billing");
        assertThat(url).isEqualTo("https://app.com/billing");
    }

    // ── listInvoices ────────────────────────────────────────────────

    @Test
    void listInvoices_returnsLocalInvoicesFirst() {
        BillingInvoice inv = BillingInvoice.builder()
                .id(UUID.randomUUID()).organizationId(ORG_ID)
                .status(InvoiceStatus.PAID).totalCents(2900).currency("USD")
                .periodStart(Instant.now()).periodEnd(Instant.now())
                .build();
        when(invoiceRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID))
                .thenReturn(List.of(inv));

        List<InvoiceResponse> result = service.listInvoices();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmountCents()).isEqualTo(2900);
    }

    @Test
    void listInvoices_fallsBackToExternalProvider() {
        when(invoiceRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID))
                .thenReturn(List.of());
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).providerCode("stripe")
                .externalCustomerId("cus_inv").build();
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID))
                .thenReturn(Optional.of(sub));

        stripeProvider.setExternalInvoices(List.of(
                new BillingProvider.ExternalInvoice("inv_1", "paid", 2900, "USD",
                        "starter", Instant.now(), Instant.now(), Instant.now(),
                        "https://stripe.com/inv", null)));

        List<InvoiceResponse> result = service.listInvoices();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("inv_1");
    }

    // ── processWebhook ──────────────────────────────────────────────

    @Test
    void processWebhook_invoicePaid_renewsActiveSubscription() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .status(SubscriptionStatus.ACTIVE)
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1"))
                .thenReturn(Optional.of(sub));

        Instant periodStart = Instant.now();
        Instant periodEnd = periodStart.plusSeconds(86400 * 30);
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "invoice.paid", "cus_1", "sub_ext_1", "pi_1", null,
                2900L, "USD", "4242", "visa", null, null, null,
                periodStart, periodEnd, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(paymentRepository).save(any(BillingPayment.class));
        verify(lifecycleService).renew(SUB_ID, periodStart, periodEnd);
    }

    @Test
    void processWebhook_replayedPaymentSucceeded_doesNotRenewTwice() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .status(SubscriptionStatus.ACTIVE)
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1"))
                .thenReturn(Optional.of(sub));
        List<BillingPayment> recorded = new ArrayList<>();
        when(paymentRepository.save(any(BillingPayment.class))).thenAnswer(inv -> {
            recorded.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(paymentRepository.existsByProviderCodeAndExternalPaymentIdAndStatusIn(any(), any(), any()))
                .thenAnswer(inv -> recorded.stream().anyMatch(p ->
                        p.getProviderCode().equals(inv.getArgument(0))
                                && p.getExternalPaymentId().equals(inv.getArgument(1))
                                && ((Collection<?>) inv.getArgument(2)).contains(p.getStatus())));

        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.succeeded", ORG_ID.toString(), "sub_ext_1", "railhook_order_1", null,
                2900L, "UAH", null, null, null, null, null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());
        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService, times(1)).renew(eq(SUB_ID), any(), any());
        assertThat(recorded).hasSize(1);
    }

    @Test
    void processWebhook_replayedPaymentFailedAfterSuccess_doesNotMarkPastDue() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .status(SubscriptionStatus.ACTIVE)
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1"))
                .thenReturn(Optional.of(sub));
        when(paymentRepository.existsByProviderCodeAndExternalPaymentIdAndStatusIn(
                eq("stripe"), eq("railhook_order_1"), argThat(s -> s.contains(PaymentStatus.SUCCEEDED))))
                .thenReturn(true);

        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.failed", ORG_ID.toString(), "sub_ext_1", "railhook_order_1", null,
                2900L, "UAH", null, null, "1101", "Declined", null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService, never()).markPastDue(any(), any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void processWebhook_invoicePaid_activatesPastDueSubscription() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .status(SubscriptionStatus.PAST_DUE)
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1"))
                .thenReturn(Optional.of(sub));

        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "invoice.paid", "cus_1", "sub_ext_1", "pi_1", null,
                2900L, "USD", null, null, null, null, null,
                Instant.now(), Instant.now().plusSeconds(86400 * 30), Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService).activate(eq(SUB_ID), any(), any());
    }

    @Test
    void processWebhook_invoicePaymentFailed_marksPastDue() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .status(SubscriptionStatus.ACTIVE)
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1"))
                .thenReturn(Optional.of(sub));

        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "invoice.payment_failed", "cus_1", "sub_ext_1", "pi_2", null,
                2900L, "USD", null, null, "card_declined", "Your card was declined",
                null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        ArgumentCaptor<BillingPayment> cap = ArgumentCaptor.forClass(BillingPayment.class);
        verify(paymentRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(cap.getValue().getFailureCode()).isEqualTo("card_declined");

        verify(lifecycleService).markPastDue(eq(SUB_ID), contains("card_declined"));
    }

    @Test
    void processWebhook_subscriptionDeleted_cancels() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1"))
                .thenReturn(Optional.of(sub));

        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "customer.subscription.deleted", "cus_1", "sub_ext_1", null, null,
                null, null, null, null, null, null, null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService).cancel(SUB_ID, "Cancelled externally by provider");
    }

    @Test
    void processWebhook_subscriptionUpdated_changesPlan() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1"))
                .thenReturn(Optional.of(sub));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));

        Instant start = Instant.now();
        Instant end = start.plusSeconds(86400 * 30);
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "customer.subscription.updated", "cus_1", "sub_ext_1", null, "pro",
                null, null, null, null, null, null, null, start, end, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService).changePlan(SUB_ID, proPlan);
        verify(lifecycleService).activate(SUB_ID, start, end);
    }

    @Test
    void processWebhook_refunded_updatesPayment() {
        BillingPayment payment = BillingPayment.builder()
                .id(UUID.randomUUID()).amountCents(2900)
                .status(PaymentStatus.SUCCEEDED)
                .externalPaymentId("pi_ref").build();
        when(subscriptionRepository.findByExternalSubscriptionId(any())).thenReturn(Optional.empty());
        when(subscriptionRepository.findFirstByExternalCustomerIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(paymentRepository.findFirstByProviderCodeAndExternalPaymentIdAndStatusInOrderByCreatedAtDesc(eq("stripe"), eq("pi_ref"), any()))
                .thenReturn(Optional.of(payment));

        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.refunded", null, null, "pi_ref", null,
                2900L, "USD", null, null, null, null, null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedCents()).isEqualTo(2900L);
        verify(paymentRepository).save(payment);
    }

    @Test
    void processWebhook_partiallyRefunded_saysSoOnTheStatus() {
        BillingPayment payment = BillingPayment.builder()
                .id(UUID.randomUUID()).amountCents(2900)
                .status(PaymentStatus.SUCCEEDED)
                .externalPaymentId("pi_part").build();
        when(subscriptionRepository.findByExternalSubscriptionId(any())).thenReturn(Optional.empty());
        when(subscriptionRepository.findFirstByExternalCustomerIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(paymentRepository.findFirstByProviderCodeAndExternalPaymentIdAndStatusInOrderByCreatedAtDesc(eq("stripe"), eq("pi_part"), any()))
                .thenReturn(Optional.of(payment));

        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.refunded", null, null, "pi_part", null,
                1000L, "USD", null, null, null, null, null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedCents()).isEqualTo(1000L);
    }

    @Test
    void processWebhook_nullEvent_skips() {
        stripeProvider.setWebhookEvent(null);
        service.processWebhook("stripe", "{}", Map.of());
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void processWebhook_storesRecurringToken() {
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe")
                .status(SubscriptionStatus.ACTIVE)
                .externalSubscriptionId("sub_ext_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1"))
                .thenReturn(Optional.of(sub));

        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.succeeded", "cus_1", "sub_ext_1", "pi_1", null,
                2900L, "USD", "1234", "mastercard", null, null, "rec_token_enc",
                Instant.now(), Instant.now().plusSeconds(86400), Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService).setRecurringToken(SUB_ID, "rec_token_enc", "1234", "mastercard");
    }

    // ── Checkout → first payment ────────────────────────────────────

    @Test
    void processWebhook_firstPaymentActivatesThePendingCheckoutAndMovesThePlan() {
        String orderRef = "railhook_" + ORG_ID + "_1";
        BillingSubscription pending = pendingSubscription("wayforpay", orderRef, null);
        TestBillingProvider wayforpay = merchantRecurringProvider();
        BillingService withWayForPay = serviceWith(wayforpay);
        when(subscriptionRepository.findByExternalSubscriptionId(orderRef)).thenReturn(Optional.of(pending));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        wayforpay.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.succeeded", ORG_ID.toString(), orderRef, orderRef, null,
                29900L, "UAH", "8217", "visa", null, null, "rec_token",
                null, null, Map.of()));

        Instant before = Instant.now();
        withWayForPay.processWebhook("wayforpay", "{}", Map.of());

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(lifecycleService).activate(eq(SUB_ID), start.capture(), end.capture());
        assertThat(start.getValue()).isAfterOrEqualTo(before);
        // A month from now, by the subscription's interval — not a fixed thirty days.
        assertThat(end.getValue()).isEqualTo(start.getValue().atZone(ZoneOffset.UTC)
                .plus(BillingInterval.MONTHLY.getPeriod()).toInstant());
        verify(lifecycleService, never()).renew(any(), any(), any());
        verify(lifecycleService).setRecurringToken(SUB_ID, "rec_token", "8217", "visa");
        ArgumentCaptor<BillingPayment> payment = ArgumentCaptor.forClass(BillingPayment.class);
        verify(paymentRepository).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getValue().getOrganizationId()).isEqualTo(ORG_ID);
    }

    @Test
    void processWebhook_stripesFirstInvoiceFindsThePendingCheckoutByCustomerAndBindsTheSubscription() {
        BillingSubscription pending = pendingSubscription("stripe", null, "cus_1");
        when(subscriptionRepository.findByExternalSubscriptionId("sub_new")).thenReturn(Optional.empty());
        when(subscriptionRepository.findFirstByExternalCustomerIdOrderByCreatedAtDesc("cus_1"))
                .thenReturn(Optional.of(pending));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        Instant start = Instant.now();
        Instant end = start.plusSeconds(86400 * 30);
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "invoice.paid", "cus_1", "sub_new", "pi_1", null,
                2900L, "usd", null, null, null, null, null, start, end, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService).setExternalIds(SUB_ID, "cus_1", "sub_new");
        verify(lifecycleService).activate(SUB_ID, start, end);
    }

    @Test
    void processWebhook_declinedFirstPaymentKeepsTheCheckoutPendingAndTheOrganizationAlone() {
        String orderRef = "railhook_" + ORG_ID + "_1";
        BillingSubscription pending = pendingSubscription("stripe", orderRef, null);
        when(subscriptionRepository.findByExternalSubscriptionId(orderRef)).thenReturn(Optional.of(pending));
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.failed", ORG_ID.toString(), orderRef, orderRef, null,
                29900L, "UAH", null, null, "1101", "Declined", null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        // Nothing was ever paid, so there is nothing past due: marking it would put an
        // organization on Free into dunning over a card it tried once.
        verify(lifecycleService, never()).markPastDue(any(), any());
        verify(lifecycleService, never()).activate(any(), any(), any());
        ArgumentCaptor<BillingPayment> payment = ArgumentCaptor.forClass(BillingPayment.class);
        verify(paymentRepository).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void processWebhook_aDroppedCheckoutIsExpiredNotCancelled() {
        BillingSubscription pending = pendingSubscription("stripe", "sub_ext_1", "cus_1");
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1")).thenReturn(Optional.of(pending));
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "customer.subscription.deleted", "cus_1", "sub_ext_1", null, null,
                null, null, null, null, null, null, null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        // cancel() would move the organization to Free and stamp it CANCELLED; it never left Free.
        verify(lifecycleService).abandon(eq(SUB_ID), any());
        verify(lifecycleService, never()).cancel(any(), any());
    }

    @Test
    void processWebhook_aSubscriptionUpdateDoesNotActivateAnUnpaidCheckout() {
        BillingSubscription pending = pendingSubscription("stripe", "sub_ext_1", "cus_1");
        when(subscriptionRepository.findByExternalSubscriptionId("sub_ext_1")).thenReturn(Optional.of(pending));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "customer.subscription.updated", "cus_1", "sub_ext_1", null, "pro",
                null, null, null, null, null, null, null, Instant.now(), Instant.now().plusSeconds(60), Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService, never()).activate(any(), any(), any());
        verify(lifecycleService, never()).changePlan(any(), any());
    }

    @Test
    void processWebhook_paymentOnASupersededCheckoutActivatesItAndExpiresTheNewerOne() {
        String orderRef = "railhook_" + ORG_ID + "_1";
        BillingSubscription superseded = pendingSubscription("stripe", orderRef, null);
        superseded.setStatus(SubscriptionStatus.EXPIRED);
        BillingSubscription newer = pendingSubscription("stripe", "railhook_" + ORG_ID + "_2", null);
        newer.setId(UUID.randomUUID());
        when(subscriptionRepository.findByExternalSubscriptionId(orderRef)).thenReturn(Optional.of(superseded));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByOrganizationIdAndStatus(ORG_ID, SubscriptionStatus.PENDING))
                .thenReturn(List.of(newer));
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.succeeded", ORG_ID.toString(), orderRef, orderRef, null,
                29900L, "UAH", null, null, null, null, null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        // The customer paid on the first tab after opening a second: the money is real, so the
        // checkout it paid for is the one that counts.
        verify(lifecycleService).abandon(eq(newer.getId()), any());
        verify(lifecycleService).activate(eq(SUB_ID), any(), any());
    }

    @Test
    void processWebhook_paymentOnASupersededCheckoutDoesNotOpenASecondLiveSubscription() {
        String orderRef = "railhook_" + ORG_ID + "_1";
        BillingSubscription superseded = pendingSubscription("stripe", orderRef, null);
        superseded.setStatus(SubscriptionStatus.EXPIRED);
        when(subscriptionRepository.findByExternalSubscriptionId(orderRef)).thenReturn(Optional.of(superseded));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.of(
                BillingSubscription.builder().id(UUID.randomUUID()).organizationId(ORG_ID)
                        .status(SubscriptionStatus.ACTIVE).plan(proPlan).build()));
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.succeeded", ORG_ID.toString(), orderRef, orderRef, null,
                29900L, "UAH", null, null, null, null, null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        // Paid twice: the payment is recorded so it can be refunded, but only one subscription
        // stays live.
        verify(paymentRepository).save(any(BillingPayment.class));
        verify(lifecycleService, never()).activate(any(), any(), any());
        verify(lifecycleService, never()).renew(any(), any(), any());
    }

    @Test
    void processWebhook_aRenewalWithoutPeriodsExtendsFromTheCurrentPeriodEnd() {
        Instant periodEnd = Instant.parse("2026-10-01T00:00:00Z");
        BillingSubscription sub = BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode("stripe").plan(starterPlan)
                .status(SubscriptionStatus.ACTIVE).billingInterval(BillingInterval.MONTHLY)
                .currentPeriodStart(Instant.parse("2026-09-01T00:00:00Z")).currentPeriodEnd(periodEnd)
                .externalSubscriptionId("ref_1").build();
        when(subscriptionRepository.findByExternalSubscriptionId("ref_1")).thenReturn(Optional.of(sub));
        stripeProvider.setWebhookEvent(new BillingProvider.BillingWebhookEvent(
                "payment.succeeded", null, "ref_1", "pay_2", null,
                2900L, "USD", null, null, null, null, null, null, null, Map.of()));

        service.processWebhook("stripe", "{}", Map.of());

        verify(lifecycleService).renew(SUB_ID, periodEnd, Instant.parse("2026-11-01T00:00:00Z"));
    }

    private BillingSubscription pendingSubscription(String providerCode, String reference, String customerId) {
        return BillingSubscription.builder()
                .id(SUB_ID).organizationId(ORG_ID).providerCode(providerCode).plan(starterPlan)
                .status(SubscriptionStatus.PENDING).billingInterval(BillingInterval.MONTHLY)
                .currency("UAH").priceCents(29900L)
                .externalSubscriptionId(reference).externalCustomerId(customerId).build();
    }

    private TestBillingProvider merchantRecurringProvider() {
        TestBillingProvider wayforpay = new TestBillingProvider("wayforpay", "WayForPay",
                EnumSet.of(BillingCapability.MERCHANT_RECURRING));
        wayforpay.currency = "UAH";
        wayforpay.ownPriceCents = 29900L;
        return wayforpay;
    }

    private BillingService serviceWith(TestBillingProvider provider) {
        return new BillingService(
                true, new BillingProviderRegistry(List.of(provider, new NoOpBillingProvider()), provider.getProviderCode()),
                planRepository, organizationRepository, subscriptionRepository, invoiceRepository,
                paymentRepository, entitlementService, lifecycleService);
    }

    // ── parseBillingInterval ────────────────────────────────────────

    @Test
    void createCheckoutSession_defaultsToMonthlyForInvalidInterval() {
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("starter")).thenReturn(Optional.of(starterPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        stripeProvider.setCreateCustomerResult("cus_x");
        stripeProvider.setCreatePaymentResult(
                new BillingProvider.CreatePaymentResult("https://url", null));

        service.createCheckoutSession( "starter", "stripe", "garbage", "ok", "cancel");

        // monthly price = 2900
        assertThat(stripeProvider.lastPaymentRequest.amountCents()).isEqualTo(2900L);
    }

    // ── Test helper: controllable BillingProvider ────────────────────

    static class TestBillingProvider implements BillingProvider {
        private final String code;
        private final String displayName;
        private final Set<BillingCapability> caps;

        String createCustomerResult;
        boolean createCustomerCalled;
        CreatePaymentResult paymentResult;
        CreatePaymentRequest lastPaymentRequest;
        String cancelledSubscriptionId;
        String portalUrl;
        List<ExternalInvoice> externalInvoices = List.of();
        BillingWebhookEvent webhookEvent;
        String currency = "USD";
        Long ownPriceCents;

        TestBillingProvider(String code, String displayName, Set<BillingCapability> caps) {
            this.code = code;
            this.displayName = displayName;
            this.caps = caps;
        }

        void setCreateCustomerResult(String id) { this.createCustomerResult = id; }
        void setCreatePaymentResult(CreatePaymentResult r) { this.paymentResult = r; }
        void setPortalUrl(String url) { this.portalUrl = url; }
        void setExternalInvoices(List<ExternalInvoice> inv) { this.externalInvoices = inv; }
        void setWebhookEvent(BillingWebhookEvent e) { this.webhookEvent = e; }

        @Override public String getProviderCode() { return code; }
        @Override public String getDisplayName() { return displayName; }
        @Override public Set<BillingCapability> capabilities() { return caps; }
        @Override public String getDefaultCurrency() { return currency; }

        @Override
        public long checkoutPriceCents(String planName, BillingInterval interval, long catalogPriceCents) {
            return ownPriceCents != null ? ownPriceCents : catalogPriceCents;
        }

        @Override
        public String createCustomer(UUID orgId, String name, String email) {
            createCustomerCalled = true;
            return createCustomerResult;
        }

        @Override
        public CreatePaymentResult createPaymentPage(CreatePaymentRequest request) {
            lastPaymentRequest = request;
            return paymentResult;
        }

        @Override
        public void cancelExternalSubscription(String extSubId) {
            cancelledSubscriptionId = extSubId;
        }

        @Override
        public String createPortalSession(String extCustId, String returnUrl) {
            return portalUrl != null ? portalUrl : returnUrl;
        }

        @Override
        public List<ExternalInvoice> fetchInvoices(String extCustId) {
            return externalInvoices;
        }

        @Override
        public BillingWebhookEvent parseWebhook(String raw, Map<String, String> headers) {
            return webhookEvent;
        }
    }
}
