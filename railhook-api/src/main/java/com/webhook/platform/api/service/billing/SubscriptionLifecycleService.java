package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionLifecycleService {

    private static final ObjectMapper METADATA_JSON = new ObjectMapper();

    private final BillingSubscriptionRepository subscriptionRepository;
    private final BillingSubscriptionEventRepository eventRepository;
    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;
    private final EntitlementService entitlementService;

    // The plan changes only on the first payment. Earlier unpaid checkouts are expired and flushed
    // first: Hibernate orders inserts before updates, and only one open subscription is allowed.
    @Transactional
    public BillingSubscription createPending(Plan plan, String providerCode, String currency,
                                             BillingInterval interval, long priceCents,
                                             String externalCustomerId, String externalSubscriptionId,
                                             String checkoutSessionId) {
        UUID organizationId = TenantContext.require();
        for (BillingSubscription earlier : subscriptionRepository.findByOrganizationIdAndStatus(
                organizationId, SubscriptionStatus.PENDING)) {
            expire(earlier, "Superseded by a newer checkout");
        }

        BillingSubscription sub = BillingSubscription.builder()
                .organizationId(organizationId)
                .plan(plan)
                .providerCode(providerCode)
                .status(SubscriptionStatus.PENDING)
                .billingInterval(interval != null ? interval : BillingInterval.MONTHLY)
                .currency(currency != null ? currency : "USD")
                .priceCents(priceCents)
                .externalCustomerId(externalCustomerId)
                .externalSubscriptionId(externalSubscriptionId)
                .metadata(checkoutSessionId != null ? checkoutMetadata(checkoutSessionId) : "{}")
                .build();
        // Flushed: the event row references it by a plain id column Hibernate's ordering cannot see.
        sub = subscriptionRepository.saveAndFlush(sub);

        logEvent(sub, SubscriptionEventType.CREATED, null, SubscriptionStatus.PENDING,
                null, plan.getId(), "Checkout started");
        log.info("Checkout opened: sub={} org={} plan={} provider={}",
                sub.getId(), organizationId, plan.getName(), providerCode);
        return sub;
    }

    /** Unlike {@link #cancel}, the plan is not touched: it never moved. */
    @Transactional
    public void abandon(UUID subscriptionId, String reason) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        if (sub.getStatus() != SubscriptionStatus.PENDING) {
            log.info("Not abandoning subscription {}: it is {}", subscriptionId, sub.getStatus());
            return;
        }
        expire(sub, reason);
    }

    private void expire(BillingSubscription sub, String reason) {
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.EXPIRED);
        subscriptionRepository.saveAndFlush(sub);
        logEvent(sub, SubscriptionEventType.EXPIRED, prev, SubscriptionStatus.EXPIRED, null, null, reason);
        log.info("Checkout expired: sub={} reason={}", sub.getId(), reason);
    }

    private static String checkoutMetadata(String checkoutSessionId) {
        try {
            return METADATA_JSON.writeValueAsString(Map.of("checkoutSessionId", checkoutSessionId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public void activate(UUID subscriptionId, Instant periodStart, Instant periodEnd) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCurrentPeriodStart(periodStart);
        sub.setCurrentPeriodEnd(periodEnd);
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.ACTIVATED, prev, SubscriptionStatus.ACTIVE,
                null, null, "Subscription activated");
        syncOrgPlan(sub.getOrganizationId(), sub.getPlan(), BillingStatus.ACTIVE);
    }

    @Transactional
    public void renew(UUID subscriptionId, Instant newPeriodStart, Instant newPeriodEnd) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCurrentPeriodStart(newPeriodStart);
        sub.setCurrentPeriodEnd(newPeriodEnd);
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.RENEWED, prev, SubscriptionStatus.ACTIVE,
                null, null, "Subscription renewed");
        syncOrgPlan(sub.getOrganizationId(), sub.getPlan(), BillingStatus.ACTIVE);
        log.info("Subscription renewed: sub={} until {}", subscriptionId, newPeriodEnd);
    }

    @Transactional
    public void changePlan(UUID subscriptionId, Plan newPlan) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        UUID oldPlanId = sub.getPlan().getId();
        sub.setPlan(newPlan);
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.PLAN_CHANGED, sub.getStatus(), sub.getStatus(),
                oldPlanId, newPlan.getId(), "Plan changed to " + newPlan.getName());
        syncOrgPlan(sub.getOrganizationId(), newPlan, BillingStatus.ACTIVE);
        log.info("Plan changed: sub={} → {}", subscriptionId, newPlan.getName());
    }

    @Transactional
    public void markPastDue(UUID subscriptionId, String reason) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.PAST_DUE);
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.PAST_DUE, prev, SubscriptionStatus.PAST_DUE,
                null, null, reason);
        syncOrgBillingStatus(sub.getOrganizationId(), BillingStatus.PAST_DUE);
        log.warn("Subscription past due: sub={} reason={}", subscriptionId, reason);
    }

    @Transactional
    public void startGracePeriod(UUID subscriptionId) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.GRACE_PERIOD);
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.GRACE_PERIOD_STARTED, prev, SubscriptionStatus.GRACE_PERIOD,
                null, null, "Grace period started");
        // Without this the organization kept reading PAST_DUE through the whole grace period.
        syncOrgBillingStatus(sub.getOrganizationId(), BillingStatus.GRACE_PERIOD);
        log.warn("Grace period started: sub={}", subscriptionId);
    }

    @Transactional
    public void suspend(UUID subscriptionId) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.SUSPENDED);
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.SUSPENDED, prev, SubscriptionStatus.SUSPENDED,
                null, null, "Subscription suspended — payment overdue");

        planRepository.findByName("free").ifPresent(freePlan -> {
            syncOrgPlan(sub.getOrganizationId(), freePlan, BillingStatus.SUSPENDED);
        });
        log.warn("Subscription suspended: sub={}", subscriptionId);
    }

    @Transactional
    public void cancel(UUID subscriptionId, String reason) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setCancelledAt(Instant.now());
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.CANCELLED, prev, SubscriptionStatus.CANCELLED,
                null, null, reason);

        planRepository.findByName("free").ifPresent(freePlan -> {
            syncOrgPlan(sub.getOrganizationId(), freePlan, BillingStatus.CANCELLED);
        });
        log.info("Subscription cancelled: sub={} reason={}", subscriptionId, reason);
    }

    @Transactional
    public void setExternalIds(UUID subscriptionId, String externalCustomerId, String externalSubscriptionId) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        sub.setExternalCustomerId(externalCustomerId);
        sub.setExternalSubscriptionId(externalSubscriptionId);
        subscriptionRepository.save(sub);
    }

    @Transactional
    public void setRecurringToken(UUID subscriptionId, String recurringTokenEncrypted,
                                   String cardLast4, String cardBrand) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        sub.setRecurringTokenEncrypted(recurringTokenEncrypted);
        sub.setCardLast4(cardLast4);
        sub.setCardBrand(cardBrand);
        subscriptionRepository.save(sub);
    }

    private BillingSubscription findOrThrow(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
    }

    private void logEvent(BillingSubscription sub, SubscriptionEventType type,
                          SubscriptionStatus fromStatus, SubscriptionStatus toStatus,
                          UUID fromPlanId, UUID toPlanId, String reason) {
        // Set explicitly: Hibernate leaves it null under the root tenant and the insert would fail.
        eventRepository.save(BillingSubscriptionEvent.builder()
                .organizationId(sub.getOrganizationId())
                .subscriptionId(sub.getId())
                .eventType(type)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .fromPlanId(fromPlanId)
                .toPlanId(toPlanId)
                .reason(reason)
                .build());
    }

    // Explicit organization: schedulers run under the system tenant.
    private void syncOrgPlan(UUID organizationId, Plan plan, BillingStatus billingStatus) {
        organizationRepository.findById(organizationId).ifPresent(org -> {
            org.setPlan(plan);
            org.setBillingStatus(billingStatus);
            organizationRepository.save(org);
        });
        entitlementService.evictPlanCache(organizationId);
    }

    private void syncOrgBillingStatus(UUID organizationId, BillingStatus billingStatus) {
        organizationRepository.findById(organizationId).ifPresent(org -> {
            org.setBillingStatus(billingStatus);
            organizationRepository.save(org);
        });
    }
}
