package com.webhook.platform.api.service.billing;

import java.util.UUID;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.InvoiceResponse;
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
    void createCheckoutSession_reusesExistingCustomer() {
        BillingSubscription existingSub = BillingSubscription.builder()
                .id(SUB_ID).externalCustomerId("cus_existing").build();

        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(planRepository.findByName("starter")).thenReturn(Optional.of(starterPlan));
        when(subscriptionRepository.findActiveByOrganizationId(ORG_ID))
                .thenReturn(Optional.of(existingSub));

        stripeProvider.setCreatePaymentResult(
                new BillingProvider.CreatePaymentResult("https://checkout.stripe.com/s2", "cs_2"));

        service.createCheckoutSession( "starter", null, null, "ok", "cancel");

        // Should not create new customer
        assertThat(stripeProvider.createCustomerCalled).isFalse();
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
        when(subscriptionRepository.findByExternalCustomerId(any())).thenReturn(Optional.empty());
        when(paymentRepository.findByExternalPaymentId("pi_ref"))
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
        when(subscriptionRepository.findByExternalCustomerId(any())).thenReturn(Optional.empty());
        when(paymentRepository.findByExternalPaymentId("pi_part"))
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
