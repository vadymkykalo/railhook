package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionLifecycleServiceTest {

    @Mock private BillingSubscriptionRepository subscriptionRepository;
    @Mock private BillingSubscriptionEventRepository eventRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private PlanRepository planRepository;
    @Mock private EntitlementService entitlementService;

    private SubscriptionLifecycleService service;

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID SUB_ID = UUID.randomUUID();
    private Plan starterPlan;
    private Plan proPlan;
    private Plan freePlan;
    private Organization org;

    @BeforeEach
    void setUp() {
        service = new SubscriptionLifecycleService(
                subscriptionRepository, eventRepository, organizationRepository,
                planRepository, entitlementService);

        starterPlan = Plan.builder().id(UUID.randomUUID()).name("starter").displayName("Starter")
                .priceMonthlyCents(2900).priceYearlyCents(29000).build();
        proPlan = Plan.builder().id(UUID.randomUUID()).name("pro").displayName("Pro")
                .priceMonthlyCents(9900).priceYearlyCents(99000).build();
        freePlan = Plan.builder().id(UUID.randomUUID()).name("free").displayName("Free")
                .priceMonthlyCents(0).priceYearlyCents(0).build();

        org = Organization.builder().id(ORG_ID).name("Test Org").build();
    }

    // ── createSubscription ──────────────────────────────────────────


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
    void createSubscription_savesWithActiveStatus() {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(86400 * 30);

        when(subscriptionRepository.save(any(BillingSubscription.class)))
                .thenAnswer(inv -> {
                    BillingSubscription s = inv.getArgument(0);
                    s.setId(SUB_ID);
                    return s;
                });
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        BillingSubscription result = service.createSubscription( starterPlan, "stripe", "USD", BillingInterval.MONTHLY, start, end);

        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(result.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(result.getPlan()).isEqualTo(starterPlan);
        assertThat(result.getProviderCode()).isEqualTo("stripe");
        assertThat(result.getCurrency()).isEqualTo("USD");

        // Event logged
        ArgumentCaptor<BillingSubscriptionEvent> eventCap = ArgumentCaptor.forClass(BillingSubscriptionEvent.class);
        verify(eventRepository).save(eventCap.capture());
        assertThat(eventCap.getValue().getEventType()).isEqualTo(SubscriptionEventType.CREATED);
        assertThat(eventCap.getValue().getToStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        // Org plan synced
        verify(organizationRepository).save(org);
        assertThat(org.getPlan()).isEqualTo(starterPlan);
        verify(entitlementService).evictPlanCache(any());
    }

    @Test
    void createSubscription_defaultsCurrencyAndInterval() {
        when(subscriptionRepository.save(any())).thenAnswer(inv -> {
            BillingSubscription s = inv.getArgument(0);
            s.setId(SUB_ID);
            return s;
        });
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        BillingSubscription result = service.createSubscription( starterPlan, "wayforpay", null, null, Instant.now(), Instant.now());

        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getBillingInterval()).isEqualTo(BillingInterval.MONTHLY);
    }

    // ── activate ────────────────────────────────────────────────────

    @Test
    void activate_changesStatusToActive() {
        BillingSubscription sub = buildSub(SubscriptionStatus.PAST_DUE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        Instant start = Instant.now();
        Instant end = start.plusSeconds(86400 * 30);
        service.activate(SUB_ID, start, end);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(start);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(end);
        verify(subscriptionRepository).save(sub);

        verifyEvent(SubscriptionEventType.ACTIVATED, SubscriptionStatus.PAST_DUE, SubscriptionStatus.ACTIVE);
    }

    // ── renew ───────────────────────────────────────────────────────

    @Test
    void renew_updatesPeriodsAndLogsEvent() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        Instant newStart = Instant.now();
        Instant newEnd = newStart.plusSeconds(86400 * 30);
        service.renew(SUB_ID, newStart, newEnd);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(newStart);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(newEnd);
        verifyEvent(SubscriptionEventType.RENEWED, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);
    }

    // ── changePlan ──────────────────────────────────────────────────

    @Test
    void changePlan_switchesPlanAndLogsEvent() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        service.changePlan(SUB_ID, proPlan);

        assertThat(sub.getPlan()).isEqualTo(proPlan);
        verify(subscriptionRepository).save(sub);

        ArgumentCaptor<BillingSubscriptionEvent> cap = ArgumentCaptor.forClass(BillingSubscriptionEvent.class);
        verify(eventRepository).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(SubscriptionEventType.PLAN_CHANGED);
        assertThat(cap.getValue().getFromPlanId()).isEqualTo(starterPlan.getId());
        assertThat(cap.getValue().getToPlanId()).isEqualTo(proPlan.getId());
    }

    // ── markPastDue ─────────────────────────────────────────────────

    @Test
    void markPastDue_setsStatusAndSyncsBilling() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        service.markPastDue(SUB_ID, "Payment failed");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(org.getBillingStatus()).isEqualTo(BillingStatus.PAST_DUE);
        verifyEvent(SubscriptionEventType.PAST_DUE, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);
    }

    // ── startGracePeriod ────────────────────────────────────────────

    @Test
    void startGracePeriod_transitionsFromPastDue() {
        BillingSubscription sub = buildSub(SubscriptionStatus.PAST_DUE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        service.startGracePeriod(SUB_ID);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.GRACE_PERIOD);
        verifyEvent(SubscriptionEventType.GRACE_PERIOD_STARTED,
                SubscriptionStatus.PAST_DUE, SubscriptionStatus.GRACE_PERIOD);
    }

    @Test
    void startGracePeriod_syncsBillingStatusOntoTheOrganization() {
        BillingSubscription sub = buildSub(SubscriptionStatus.PAST_DUE);
        org.setBillingStatus(BillingStatus.PAST_DUE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        service.startGracePeriod(SUB_ID);

        assertThat(org.getBillingStatus()).isEqualTo(BillingStatus.GRACE_PERIOD);
    }

    // ── suspend ─────────────────────────────────────────────────────

    @Test
    void suspend_downgradsToFreePlan() {
        BillingSubscription sub = buildSub(SubscriptionStatus.GRACE_PERIOD);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findByName("free")).thenReturn(Optional.of(freePlan));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        service.suspend(SUB_ID);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(org.getPlan()).isEqualTo(freePlan);
        assertThat(org.getBillingStatus()).isEqualTo(BillingStatus.SUSPENDED);
        verifyEvent(SubscriptionEventType.SUSPENDED,
                SubscriptionStatus.GRACE_PERIOD, SubscriptionStatus.SUSPENDED);
    }

    // ── cancel ──────────────────────────────────────────────────────

    @Test
    void cancel_setsCancelledAtAndDowngrades() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findByName("free")).thenReturn(Optional.of(freePlan));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        service.cancel(SUB_ID, "User requested");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(sub.getCancelledAt()).isNotNull();
        assertThat(org.getPlan()).isEqualTo(freePlan);
        verifyEvent(SubscriptionEventType.CANCELLED,
                SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED);
    }

    // ── setExternalIds ──────────────────────────────────────────────

    @Test
    void setExternalIds_updatesFields() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));

        service.setExternalIds(SUB_ID, "cus_123", "sub_456");

        assertThat(sub.getExternalCustomerId()).isEqualTo("cus_123");
        assertThat(sub.getExternalSubscriptionId()).isEqualTo("sub_456");
        verify(subscriptionRepository).save(sub);
    }

    // ── setRecurringToken ───────────────────────────────────────────

    @Test
    void setRecurringToken_updatesCardInfo() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));

        service.setRecurringToken(SUB_ID, "enc_token", "4242", "visa");

        assertThat(sub.getRecurringTokenEncrypted()).isEqualTo("enc_token");
        assertThat(sub.getCardLast4()).isEqualTo("4242");
        assertThat(sub.getCardBrand()).isEqualTo("visa");
        verify(subscriptionRepository).save(sub);
    }

    // ── not found ───────────────────────────────────────────────────

    @Test
    void findOrThrow_throwsNotFoundForMissingSub() {
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(SUB_ID, Instant.now(), Instant.now()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Subscription not found");
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private BillingSubscription buildSub(SubscriptionStatus status) {
        return BillingSubscription.builder()
                .id(SUB_ID)
                .organizationId(ORG_ID)
                .plan(starterPlan)
                .providerCode("stripe")
                .status(status)
                .billingInterval(BillingInterval.MONTHLY)
                .currency("USD")
                .currentPeriodStart(Instant.now())
                .currentPeriodEnd(Instant.now().plusSeconds(86400 * 30))
                .build();
    }

    private void verifyEvent(SubscriptionEventType type,
                              SubscriptionStatus from, SubscriptionStatus to) {
        ArgumentCaptor<BillingSubscriptionEvent> cap =
                ArgumentCaptor.forClass(BillingSubscriptionEvent.class);
        verify(eventRepository).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(type);
        assertThat(cap.getValue().getFromStatus()).isEqualTo(from);
        assertThat(cap.getValue().getToStatus()).isEqualTo(to);
    }
}
