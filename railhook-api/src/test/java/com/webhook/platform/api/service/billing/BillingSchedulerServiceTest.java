package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class BillingSchedulerServiceTest {

    @Mock
    private BillingSubscriptionRepository subscriptionRepository;

    @Mock
    private BillingScheduledChangeRepository scheduledChangeRepository;

    @Mock
    private BillingInvoiceRepository invoiceRepository;

    @Mock
    private BillingPaymentRepository paymentRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private BillingProviderRegistry providerRegistry;

    @Mock
    private SubscriptionLifecycleService lifecycleService;

    @Mock
    private EntitlementService entitlementService;

    private BillingSchedulerService service;

    private Plan starterPlan;
    private Plan proPlan;
    private BillingProvider merchantProvider;

    @BeforeEach
    void setUp() {
        service = new BillingSchedulerService(
                subscriptionRepository, scheduledChangeRepository,
                invoiceRepository, paymentRepository, planRepository,
                providerRegistry, lifecycleService, entitlementService);

        starterPlan = Plan.builder().id(UUID.randomUUID()).name("starter").displayName("Starter")
                .priceMonthlyCents(2900).priceYearlyCents(29000).build();
        proPlan = Plan.builder().id(UUID.randomUUID()).name("pro").displayName("Pro")
                .priceMonthlyCents(9900).priceYearlyCents(99000).build();

        merchantProvider = new BillingProvider() {
            @Override public String getProviderCode() { return "wayforpay"; }
            @Override public String getDisplayName() { return "WayForPay"; }
            @Override public Set<BillingCapability> capabilities() {
                return EnumSet.of(BillingCapability.MERCHANT_RECURRING);
            }
            @Override public BillingWebhookEvent parseWebhook(String raw, Map<String, String> h) { return null; }
            @Override public ChargeResult chargeRecurring(RecurringChargeRequest req) {
                return new ChargeResult(true, "ext_pay_123", "4242", "visa", null, null);
            }
        };

        when(entitlementService.isBillingEnabled()).thenReturn(true);
    }

    // ── processRenewals ─────────────────────────────────────────────

    @Test
    void processRenewals_skipsWhenBillingDisabled() {
        when(entitlementService.isBillingEnabled()).thenReturn(false);
        service.processRenewals();
        verifyNoInteractions(providerRegistry);
    }

    @Test
    void processRenewals_skipsNonMerchantProviders() {
        BillingProvider stripeProvider = new BillingProvider() {
            @Override public String getProviderCode() { return "stripe"; }
            @Override public String getDisplayName() { return "Stripe"; }
            @Override public Set<BillingCapability> capabilities() {
                return EnumSet.of(BillingCapability.MANAGED_SUBSCRIPTIONS);
            }
            @Override public BillingWebhookEvent parseWebhook(String raw, Map<String, String> h) { return null; }
        };
        when(providerRegistry.all()).thenReturn(List.of(stripeProvider));

        service.processRenewals();

        verify(subscriptionRepository, never()).findDueForRenewal(any(), any());
    }

    @Test
    void processRenewals_chargesAndRenewsOnSuccess() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "wayforpay");
        sub.setRecurringTokenEncrypted("enc_token");
        sub.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));

        when(providerRegistry.all()).thenReturn(List.of(merchantProvider));
        when(subscriptionRepository.findDueForRenewal(any(), eq("wayforpay")))
                .thenReturn(List.of(sub));
        when(invoiceRepository.save(any())).thenAnswer(inv -> {
            BillingInvoice i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        service.processRenewals();

        // Invoice created and then marked PAID
        ArgumentCaptor<BillingInvoice> invCap = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceRepository, atLeastOnce()).save(invCap.capture());
        List<BillingInvoice> savedInvoices = invCap.getAllValues();
        assertThat(savedInvoices).hasSizeGreaterThanOrEqualTo(2);
        assertThat(savedInvoices.get(0).getTotalCents()).isEqualTo(2900L);
        // Final save has PAID status
        assertThat(savedInvoices.get(savedInvoices.size() - 1).getStatus()).isEqualTo(InvoiceStatus.PAID);

        // Payment recorded
        ArgumentCaptor<BillingPayment> payCap = ArgumentCaptor.forClass(BillingPayment.class);
        verify(paymentRepository).save(payCap.capture());
        assertThat(payCap.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payCap.getValue().getExternalPaymentId()).isEqualTo("ext_pay_123");

        // Lifecycle renew called
        verify(lifecycleService).renew(eq(sub.getId()), any(), any());
    }

    @Test
    void processRenewals_marksPastDueOnNoToken() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "wayforpay");
        sub.setRecurringTokenEncrypted(null);

        when(providerRegistry.all()).thenReturn(List.of(merchantProvider));
        when(subscriptionRepository.findDueForRenewal(any(), eq("wayforpay")))
                .thenReturn(List.of(sub));

        service.processRenewals();

        verify(lifecycleService).markPastDue(sub.getId(), "No recurring token");
        verifyNoInteractions(invoiceRepository);
    }

    @Test
    void processRenewals_marksPastDueOnFailedCharge() {
        BillingProvider failProvider = new BillingProvider() {
            @Override public String getProviderCode() { return "wayforpay"; }
            @Override public String getDisplayName() { return "WayForPay"; }
            @Override public Set<BillingCapability> capabilities() {
                return EnumSet.of(BillingCapability.MERCHANT_RECURRING);
            }
            @Override public BillingWebhookEvent parseWebhook(String raw, Map<String, String> h) { return null; }
            @Override public ChargeResult chargeRecurring(RecurringChargeRequest req) {
                return new ChargeResult(false, null, null, null, "insufficient_funds", "Card declined");
            }
        };

        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "wayforpay");
        sub.setRecurringTokenEncrypted("enc_token");
        sub.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));

        when(providerRegistry.all()).thenReturn(List.of(failProvider));
        when(subscriptionRepository.findDueForRenewal(any(), eq("wayforpay")))
                .thenReturn(List.of(sub));
        when(invoiceRepository.save(any())).thenAnswer(inv -> {
            BillingInvoice i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        service.processRenewals();

        verify(lifecycleService).markPastDue(eq(sub.getId()), contains("Payment failed"));

        ArgumentCaptor<BillingPayment> payCap = ArgumentCaptor.forClass(BillingPayment.class);
        verify(paymentRepository).save(payCap.capture());
        assertThat(payCap.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payCap.getValue().getFailureCode()).isEqualTo("insufficient_funds");
    }

    @Test
    void processRenewals_usesYearlyPriceForYearlySub() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE, "wayforpay");
        sub.setRecurringTokenEncrypted("enc_token");
        sub.setBillingInterval(BillingInterval.YEARLY);
        sub.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));

        when(providerRegistry.all()).thenReturn(List.of(merchantProvider));
        when(subscriptionRepository.findDueForRenewal(any(), eq("wayforpay")))
                .thenReturn(List.of(sub));
        when(invoiceRepository.save(any())).thenAnswer(inv -> {
            BillingInvoice i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        service.processRenewals();

        ArgumentCaptor<BillingInvoice> invCap = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceRepository, atLeastOnce()).save(invCap.capture());
        assertThat(invCap.getAllValues().get(0).getTotalCents()).isEqualTo(29000L);
    }

    // ── processGracePeriodExpiry ─────────────────────────────────────

    @Test
    void processGracePeriodExpiry_suspendsExpired() {
        BillingSubscription sub = buildSub(SubscriptionStatus.GRACE_PERIOD, "stripe");
        when(subscriptionRepository.findGracePeriodExpired(any())).thenReturn(List.of(sub));

        service.processGracePeriodExpiry();

        verify(lifecycleService).suspend(sub.getId());
    }

    @Test
    void processGracePeriodExpiry_skipsWhenBillingDisabled() {
        when(entitlementService.isBillingEnabled()).thenReturn(false);
        service.processGracePeriodExpiry();
        verifyNoInteractions(subscriptionRepository);
    }

    // ── processPastDueToGrace ───────────────────────────────────────

    @Test
    void processPastDueToGrace_startsGracePeriod() {
        BillingSubscription sub = buildSub(SubscriptionStatus.PAST_DUE, "stripe");
        when(subscriptionRepository.findExpiredByStatus(eq(SubscriptionStatus.PAST_DUE), any()))
                .thenReturn(List.of(sub));

        service.processPastDueToGrace();

        verify(lifecycleService).startGracePeriod(sub.getId());
    }

    // ── applyScheduledChanges ───────────────────────────────────────

    @Test
    void applyScheduledChanges_appliesPlanChange() {
        UUID subId = UUID.randomUUID();
        BillingScheduledChange change = BillingScheduledChange.builder()
                .id(UUID.randomUUID())
                .subscriptionId(subId)
                .toPlanId(proPlan.getId())
                .changeType(ScheduledChangeType.PLAN_CHANGE)
                .status(ScheduledChangeStatus.PENDING)
                .effectiveAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();

        when(scheduledChangeRepository.findReadyToApply(any())).thenReturn(List.of(change));
        when(planRepository.findById(proPlan.getId())).thenReturn(Optional.of(proPlan));

        service.applyScheduledChanges();

        verify(lifecycleService).changePlan(subId, proPlan);
        assertThat(change.getStatus()).isEqualTo(ScheduledChangeStatus.APPLIED);
        assertThat(change.getAppliedAt()).isNotNull();
        verify(scheduledChangeRepository).save(change);
    }

    @Test
    void applyScheduledChanges_appliesCancellation() {
        UUID subId = UUID.randomUUID();
        BillingScheduledChange change = BillingScheduledChange.builder()
                .id(UUID.randomUUID())
                .subscriptionId(subId)
                .toPlanId(proPlan.getId())
                .changeType(ScheduledChangeType.CANCELLATION)
                .status(ScheduledChangeStatus.PENDING)
                .effectiveAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();

        when(scheduledChangeRepository.findReadyToApply(any())).thenReturn(List.of(change));
        when(planRepository.findById(proPlan.getId())).thenReturn(Optional.of(proPlan));

        service.applyScheduledChanges();

        verify(lifecycleService).cancel(subId, "Scheduled cancellation");
    }

    @Test
    void applyScheduledChanges_skipsWhenBillingDisabled() {
        when(entitlementService.isBillingEnabled()).thenReturn(false);
        service.applyScheduledChanges();
        verifyNoInteractions(scheduledChangeRepository);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private BillingSubscription buildSub(SubscriptionStatus status, String provider) {
        return BillingSubscription.builder()
                .id(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .plan(starterPlan)
                .providerCode(provider)
                .status(status)
                .billingInterval(BillingInterval.MONTHLY)
                .currency("USD")
                .currentPeriodStart(Instant.now().minus(30, ChronoUnit.DAYS))
                .currentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
    }
}
