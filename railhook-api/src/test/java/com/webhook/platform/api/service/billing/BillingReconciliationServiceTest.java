package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingReconciliationServiceTest {

    @Mock private BillingSubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionLifecycleService lifecycleService;
    @Mock private PlanRepository planRepository;
    @Mock private EntitlementService entitlementService;

    private BillingProviderRegistry providerRegistry;
    private TestManagedProvider stripeProvider;
    private BillingReconciliationService service;

    private Plan starterPlan;
    private Plan proPlan;

    @BeforeEach
    void setUp() {
        stripeProvider = new TestManagedProvider();

        BillingProvider wayforpay = new BillingProvider() {
            @Override public String getProviderCode() { return "wayforpay"; }
            @Override public String getDisplayName() { return "WayForPay"; }
            @Override public Set<BillingCapability> capabilities() {
                return EnumSet.of(BillingCapability.MERCHANT_RECURRING);
            }
            @Override public BillingWebhookEvent parseWebhook(String raw, Map<String, String> h) { return null; }
        };

        providerRegistry = new BillingProviderRegistry(
                List.of(stripeProvider, wayforpay), "stripe");

        service = new BillingReconciliationService(
                providerRegistry, subscriptionRepository, lifecycleService,
                planRepository, entitlementService, new SimpleMeterRegistry());

        starterPlan = Plan.builder().id(UUID.randomUUID()).name("starter").displayName("Starter").build();
        proPlan = Plan.builder().id(UUID.randomUUID()).name("pro").displayName("Pro").build();

        when(entitlementService.isBillingEnabled()).thenReturn(true);
    }

    // ── Skip conditions ─────────────────────────────────────────────

    @Test
    void reconcile_skipsWhenBillingDisabled() {
        when(entitlementService.isBillingEnabled()).thenReturn(false);
        service.reconcile();
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void reconcile_skipsNonManagedProviders() {
        // wayforpay has MERCHANT_RECURRING, not MANAGED_SUBSCRIPTIONS — should be skipped
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of());

        service.reconcile();

        verify(subscriptionRepository).findReconcilable("stripe");
        verify(subscriptionRepository, never()).findReconcilable("wayforpay");
    }

    @Test
    void reconcile_skipsWhenNoSubscriptions() {
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of());
        service.reconcile();
        verifyNoInteractions(lifecycleService);
    }

    // ── Status drift ────────────────────────────────────────────────

    @Test
    void reconcile_fixesStatusDrift_activeToActive_renews() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));

        Instant newStart = Instant.now();
        Instant newEnd = newStart.plus(30, ChronoUnit.DAYS);
        // External says ACTIVE but with newer period — no status drift, just period drift
        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "active", "starter", newStart, newEnd, false));

        service.reconcile();

        // No status change (both ACTIVE), but period is updated
        verify(lifecycleService, never()).renew(any(), any(), any());
    }

    @Test
    void reconcile_fixesStatusDrift_pastDueToActive() {
        BillingSubscription sub = buildSub(SubscriptionStatus.PAST_DUE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));

        Instant start = Instant.now();
        Instant end = start.plus(30, ChronoUnit.DAYS);
        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "active", "starter", start, end, false));

        service.reconcile();

        verify(lifecycleService).activate(sub.getId(), start, end);
    }

    @Test
    void reconcile_fixesStatusDrift_activeToPastDue() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));

        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "past_due", "starter", null, null, false));

        service.reconcile();

        verify(lifecycleService).markPastDue(eq(sub.getId()), contains("Reconciliation"));
    }

    @Test
    void reconcile_fixesStatusDrift_activeToCancelled() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));

        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "canceled", "starter", null, null, false));

        service.reconcile();

        verify(lifecycleService).cancel(eq(sub.getId()), contains("Reconciliation"));
    }

    @Test
    void reconcile_fixesStatusDrift_activeToSuspended() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));

        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "unpaid", "starter", null, null, false));

        service.reconcile();

        verify(lifecycleService).suspend(sub.getId());
    }

    // ── Period drift ────────────────────────────────────────────────

    @Test
    void reconcile_fixesPeriodDrift() {
        Instant oldEnd = Instant.now().minus(5, ChronoUnit.DAYS);
        Instant newStart = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant newEnd = Instant.now().plus(29, ChronoUnit.DAYS);

        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        sub.setCurrentPeriodEnd(oldEnd);
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));

        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "active", "starter", newStart, newEnd, false));

        service.reconcile();

        // Period updated
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(newStart);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(newEnd);
        verify(subscriptionRepository).save(sub);
    }

    @Test
    void reconcile_ignoresOlderPeriodEnd() {
        Instant currentEnd = Instant.now().plus(20, ChronoUnit.DAYS);
        Instant olderEnd = Instant.now().plus(10, ChronoUnit.DAYS);

        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        sub.setCurrentPeriodEnd(currentEnd);
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));

        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "active", "starter", Instant.now(), olderEnd, false));

        service.reconcile();

        // Period NOT updated
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(currentEnd);
    }

    // ── Plan drift ──────────────────────────────────────────────────

    @Test
    void reconcile_fixesPlanDrift() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));
        when(planRepository.findByName("pro")).thenReturn(Optional.of(proPlan));

        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "active", "pro", null, null, false));

        service.reconcile();

        verify(lifecycleService).changePlan(sub.getId(), proPlan);
    }

    @Test
    void reconcile_ignoresMatchingPlan() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));

        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", "active", "starter", null, null, false));

        service.reconcile();

        verify(lifecycleService, never()).changePlan(any(), any());
    }

    // ── External state null ─────────────────────────────────────────

    @Test
    void reconcile_handlesNullExternalState() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));
        stripeProvider.setExternalState(null);

        service.reconcile();

        verifyNoInteractions(lifecycleService);
    }

    // ── Error handling ──────────────────────────────────────────────

    @Test
    void reconcile_continuesOnErrorForIndividualSub() {
        BillingSubscription sub1 = buildSub(SubscriptionStatus.ACTIVE, "sub_1");
        BillingSubscription sub2 = buildSub(SubscriptionStatus.ACTIVE, "sub_2");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub1, sub2));

        // sub1 throws, sub2 should still be processed
        stripeProvider.setThrowForSubId("sub_1");
        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_2", "active", "starter", null, null, false));

        service.reconcile();

        // Should not blow up, and second sub was still checked
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private BillingSubscription buildSub(SubscriptionStatus status, String extSubId) {
        return BillingSubscription.builder()
                .id(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .plan(starterPlan)
                .providerCode("stripe")
                .status(status)
                .externalSubscriptionId(extSubId)
                .externalCustomerId("cus_123")
                .billingInterval(BillingInterval.MONTHLY)
                .currency("USD")
                .currentPeriodStart(Instant.now().minus(30, ChronoUnit.DAYS))
                .currentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
    }

    static class TestManagedProvider implements BillingProvider {
        private BillingProvider.ExternalSubscriptionState externalState;
        private String throwForSubId;

        void setExternalState(ExternalSubscriptionState state) { this.externalState = state; }
        void setThrowForSubId(String id) { this.throwForSubId = id; }

        @Override public String getProviderCode() { return "stripe"; }
        @Override public String getDisplayName() { return "Stripe"; }
        @Override public Set<BillingCapability> capabilities() {
            return EnumSet.of(BillingCapability.MANAGED_SUBSCRIPTIONS, BillingCapability.CUSTOMERS);
        }
        @Override public BillingWebhookEvent parseWebhook(String raw, Map<String, String> h) { return null; }

        @Override
        public ExternalSubscriptionState fetchSubscriptionStatus(String extSubId) {
            if (throwForSubId != null && throwForSubId.equals(extSubId)) {
                throw new RuntimeException("API error for " + extSubId);
            }
            return externalState;
        }
    }
}
