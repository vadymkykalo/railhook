package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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

    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(ORG_ID);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void createPending_opensACheckoutWithoutTouchingThePlan() {
        when(subscriptionRepository.findByOrganizationIdAndStatus(ORG_ID, SubscriptionStatus.PENDING))
                .thenReturn(List.of());
        when(subscriptionRepository.saveAndFlush(any(BillingSubscription.class)))
                .thenAnswer(inv -> {
                    BillingSubscription s = inv.getArgument(0);
                    s.setId(SUB_ID);
                    return s;
                });

        BillingSubscription result = service.createPending(starterPlan, "wayforpay", "UAH",
                BillingInterval.MONTHLY, 29900L, null, "railhook_" + ORG_ID + "_1", null);

        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(result.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(result.getPlan()).isEqualTo(starterPlan);
        assertThat(result.getProviderCode()).isEqualTo("wayforpay");
        assertThat(result.getCurrency()).isEqualTo("UAH");
        assertThat(result.getPriceCents()).isEqualTo(29900L);
        assertThat(result.getExternalSubscriptionId()).isEqualTo("railhook_" + ORG_ID + "_1");
        assertThat(result.getCurrentPeriodStart()).isNull();

        ArgumentCaptor<BillingSubscriptionEvent> eventCap = ArgumentCaptor.forClass(BillingSubscriptionEvent.class);
        verify(eventRepository).save(eventCap.capture());
        assertThat(eventCap.getValue().getEventType()).isEqualTo(SubscriptionEventType.CREATED);
        assertThat(eventCap.getValue().getToStatus()).isEqualTo(SubscriptionStatus.PENDING);

        // Nothing is paid yet: the organization keeps its plan until the first payment.
        verify(organizationRepository, never()).save(any());
        verify(entitlementService, never()).evictPlanCache(any());
    }

    @Test
    void createPending_expiresTheCheckoutItReplaces() {
        BillingSubscription earlier = buildSub(SubscriptionStatus.PENDING);
        earlier.setCurrentPeriodStart(null);
        earlier.setCurrentPeriodEnd(null);
        when(subscriptionRepository.findByOrganizationIdAndStatus(ORG_ID, SubscriptionStatus.PENDING))
                .thenReturn(List.of(earlier));
        when(subscriptionRepository.saveAndFlush(any(BillingSubscription.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createPending(proPlan, "stripe", "USD", BillingInterval.MONTHLY, 9900L, "cus_1", null, "cs_1");

        assertThat(earlier.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        // Flushed first, or the one-open-subscription-per-organization index rejects the insert.
        InOrder order = inOrder(subscriptionRepository);
        order.verify(subscriptionRepository).saveAndFlush(earlier);
        order.verify(subscriptionRepository).saveAndFlush(argThat(s -> s.getStatus() == SubscriptionStatus.PENDING));
    }

    @Test
    void createPending_recordsAManagedProvidersCheckoutSession() {
        when(subscriptionRepository.findByOrganizationIdAndStatus(ORG_ID, SubscriptionStatus.PENDING))
                .thenReturn(List.of());
        when(subscriptionRepository.saveAndFlush(any(BillingSubscription.class))).thenAnswer(inv -> inv.getArgument(0));

        BillingSubscription result = service.createPending(proPlan, "stripe", "USD",
                BillingInterval.YEARLY, 99000L, "cus_1", null, "cs_test_1");

        assertThat(result.getExternalCustomerId()).isEqualTo("cus_1");
        assertThat(result.getExternalSubscriptionId()).isNull();
        assertThat(result.getMetadata()).contains("\"checkoutSessionId\":\"cs_test_1\"");
        assertThat(result.getBillingInterval()).isEqualTo(BillingInterval.YEARLY);
    }

    @Test
    void abandon_expiresAPendingCheckoutAndLeavesThePlanAlone() {
        BillingSubscription sub = buildSub(SubscriptionStatus.PENDING);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));

        service.abandon(SUB_ID, "Checkout dropped by provider");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        verify(subscriptionRepository).saveAndFlush(sub);
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void abandon_leavesAPaidSubscriptionAlone() {
        BillingSubscription sub = buildSub(SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));

        service.abandon(SUB_ID, "late");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

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

    @Test
    void startGracePeriod_transitionsFromPastDue() {
        BillingSubscription sub = buildSub(SubscriptionStatus.PAST_DUE);
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(sub));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));

        service.startGracePeriod(SUB_ID);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.GRACE_PERIOD);
        assertThat(org.getBillingStatus()).isEqualTo(BillingStatus.GRACE_PERIOD);
        verifyEvent(SubscriptionEventType.GRACE_PERIOD_STARTED,
                SubscriptionStatus.PAST_DUE, SubscriptionStatus.GRACE_PERIOD);
    }

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
