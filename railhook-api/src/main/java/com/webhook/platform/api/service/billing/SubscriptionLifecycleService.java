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

import java.time.Instant;
import java.util.UUID;

/**
 * Manages subscription state transitions and logs every change as an event.
 * Single source of truth for subscription lifecycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionLifecycleService {

    private final BillingSubscriptionRepository subscriptionRepository;
    private final BillingSubscriptionEventRepository eventRepository;
    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;
    private final EntitlementService entitlementService;

    // ── Create ──────────────────────────────────────────────────────

    @Transactional
    public BillingSubscription createSubscription(Plan plan,
                                                   String providerCode, String currency,
                                                   BillingInterval interval,
                                                   Instant periodStart, Instant periodEnd) {
        UUID organizationId = TenantContext.require();
        BillingSubscription sub = BillingSubscription.builder()
                .organizationId(organizationId)
                .plan(plan)
                .providerCode(providerCode)
                .status(SubscriptionStatus.ACTIVE)
                .billingInterval(interval != null ? interval : BillingInterval.MONTHLY)
                .currency(currency != null ? currency : "USD")
                .currentPeriodStart(periodStart)
                .currentPeriodEnd(periodEnd)
                .build();
        sub = subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.CREATED, null, SubscriptionStatus.ACTIVE,
                null, plan.getId(), "Subscription created");

        syncOrgPlan(organizationId, plan, BillingStatus.ACTIVE);
        log.info("Subscription created: sub={} org={} plan={} provider={}",
                sub.getId(), organizationId, plan.getName(), providerCode);
        return sub;
    }

    // ── Activate (from trial or past_due) ───────────────────────────

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

    // ── Renew ───────────────────────────────────────────────────────

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

    // ── Plan change ─────────────────────────────────────────────────

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

    // ── Payment failed → PAST_DUE ──────────────────────────────────

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

    // ── Grace period ────────────────────────────────────────────────

    @Transactional
    public void startGracePeriod(UUID subscriptionId) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.GRACE_PERIOD);
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.GRACE_PERIOD_STARTED, prev, SubscriptionStatus.GRACE_PERIOD,
                null, null, "Grace period started");
        // Every other transition here syncs the organization; this one did not, so an org whose
        // subscription entered its grace period kept reading PAST_DUE — the two rows disagreed
        // about the same fact for the whole seven days, and BillingStatus.GRACE_PERIOD, which the
        // dashboard and the GDPR export both already render, was never written by anything.
        syncOrgBillingStatus(sub.getOrganizationId(), BillingStatus.GRACE_PERIOD);
        log.warn("Grace period started: sub={}", subscriptionId);
    }

    // ── Suspend (grace expired) ─────────────────────────────────────

    @Transactional
    public void suspend(UUID subscriptionId) {
        BillingSubscription sub = findOrThrow(subscriptionId);
        SubscriptionStatus prev = sub.getStatus();
        sub.setStatus(SubscriptionStatus.SUSPENDED);
        subscriptionRepository.save(sub);

        logEvent(sub, SubscriptionEventType.SUSPENDED, prev, SubscriptionStatus.SUSPENDED,
                null, null, "Subscription suspended — payment overdue");

        // Downgrade org to free plan
        planRepository.findByName("free").ifPresent(freePlan -> {
            syncOrgPlan(sub.getOrganizationId(), freePlan, BillingStatus.SUSPENDED);
        });
        log.warn("Subscription suspended: sub={}", subscriptionId);
    }

    // ── Cancel ──────────────────────────────────────────────────────

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

    // ── Update external IDs (after provider creates customer/subscription) ──

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

    // ── Internal ────────────────────────────────────────────────────

    private BillingSubscription findOrThrow(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
    }

    private void logEvent(BillingSubscription sub, SubscriptionEventType type,
                          SubscriptionStatus fromStatus, SubscriptionStatus toStatus,
                          UUID fromPlanId, UUID toPlanId, String reason) {
        // Most callers are the billing schedulers and the provider webhook, all system-scoped:
        // Hibernate leaves the entity's own organization_id alone under the root tenant, and the
        // column is NOT NULL since V056 — so the value comes off the subscription being changed,
        // the same way syncOrgPlan below takes it. Without it the whole webhook transaction rolls
        // back and the status change is lost.
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

    /**
     * Takes the organization explicitly, unlike the request-facing methods above.
     *
     * <p>Most callers here are the billing schedulers, which run under the system tenant and walk
     * subscriptions belonging to many organizations: the organization comes off the row being
     * processed, not off an ambient scope. Reading it from {@code TenantContext} would have
     * resolved to the system sentinel and written the plan onto nothing.
     */
    private void syncOrgPlan(UUID organizationId, Plan plan, BillingStatus billingStatus) {
        organizationRepository.findById(organizationId).ifPresent(org -> {
            org.setPlan(plan);
            org.setBillingStatus(billingStatus);
            organizationRepository.save(org);
        });
        entitlementService.evictPlanCache(organizationId);
    }

    /** Explicitly scoped for the same reason as {@link #syncOrgPlan}. */
    private void syncOrgBillingStatus(UUID organizationId, BillingStatus billingStatus) {
        organizationRepository.findById(organizationId).ifPresent(org -> {
            org.setBillingStatus(billingStatus);
            organizationRepository.save(org);
        });
    }
}
