package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @Test
    void reconcile_skipsWhenBillingDisabled() {
        when(entitlementService.isBillingEnabled()).thenReturn(false);
        service.reconcile();
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void reconcile_skipsNonManagedProviders() {
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of());

        service.reconcile();

        verify(subscriptionRepository).findReconcilable("stripe");
        verify(subscriptionRepository, never()).findReconcilable("wayforpay");
    }

    @ParameterizedTest
    @CsvSource({
            "PAST_DUE, active,   activate",
            "ACTIVE,   past_due, markPastDue",
            "ACTIVE,   canceled, cancel",
            "ACTIVE,   unpaid,   suspend",
    })
    void reconcile_fixesStatusDrift(SubscriptionStatus local, String external, String expected) {
        BillingSubscription sub = buildSub(local, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));
        Instant start = Instant.now();
        Instant end = start.plus(30, ChronoUnit.DAYS);
        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_ext_1", external, "starter", start, end, false));

        service.reconcile();

        switch (expected) {
            case "activate" -> verify(lifecycleService).activate(sub.getId(), start, end);
            case "markPastDue" -> verify(lifecycleService).markPastDue(eq(sub.getId()), anyString());
            case "cancel" -> verify(lifecycleService).cancel(eq(sub.getId()), anyString());
            case "suspend" -> verify(lifecycleService).suspend(sub.getId());
            default -> throw new IllegalArgumentException(expected);
        }
    }

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

        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(currentEnd);
    }

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

    @Test
    void reconcile_handlesNullExternalState() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "sub_ext_1");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub));
        stripeProvider.setExternalState(null);

        service.reconcile();

        verifyNoInteractions(lifecycleService);
    }

    @Test
    void reconcile_continuesOnErrorForIndividualSub() {
        BillingSubscription sub1 = buildSub(SubscriptionStatus.ACTIVE, "sub_1");
        BillingSubscription sub2 = buildSub(SubscriptionStatus.ACTIVE, "sub_2");
        when(subscriptionRepository.findReconcilable("stripe")).thenReturn(List.of(sub1, sub2));
        stripeProvider.setThrowForSubId("sub_1");
        stripeProvider.setExternalState(new BillingProvider.ExternalSubscriptionState(
                "sub_2", "canceled", "starter", null, null, false));

        service.reconcile();

        verify(lifecycleService).cancel(eq(sub2.getId()), anyString());
    }

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
