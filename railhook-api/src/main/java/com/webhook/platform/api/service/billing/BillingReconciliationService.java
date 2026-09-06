package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.entity.BillingSubscription;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.enums.SubscriptionStatus;
import com.webhook.platform.api.domain.repository.BillingSubscriptionRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Periodic reconciliation of billing subscription state against external providers.
 *
 * <p>Only runs for providers with {@link BillingCapability#MANAGED_SUBSCRIPTIONS}
 * (e.g. Stripe), where the provider manages the subscription lifecycle externally
 * and we learn about changes via webhooks. If webhooks are missed (outage, network
 * issue), our local state drifts from reality.</p>
 *
 * <p>WayForPay is merchant-initiated ({@link BillingCapability#MERCHANT_RECURRING}),
 * meaning WE control the billing cycle via {@link BillingSchedulerService}. Our local
 * state IS the source of truth, so no reconciliation is needed.</p>
 */
@Service
@Slf4j
public class BillingReconciliationService {

    private static final Map<String, SubscriptionStatus> STRIPE_STATUS_MAP = Map.of(
            "active", SubscriptionStatus.ACTIVE,
            "trialing", SubscriptionStatus.TRIALING,
            "past_due", SubscriptionStatus.PAST_DUE,
            "canceled", SubscriptionStatus.CANCELLED,
            "unpaid", SubscriptionStatus.SUSPENDED,
            "incomplete_expired", SubscriptionStatus.EXPIRED
    );

    private final BillingProviderRegistry providerRegistry;
    private final BillingSubscriptionRepository subscriptionRepository;
    private final SubscriptionLifecycleService lifecycleService;
    private final PlanRepository planRepository;
    private final EntitlementService entitlementService;
    private final Counter reconciliationFixCounter;
    private final Counter reconciliationErrorCounter;

    public BillingReconciliationService(
            BillingProviderRegistry providerRegistry,
            BillingSubscriptionRepository subscriptionRepository,
            SubscriptionLifecycleService lifecycleService,
            PlanRepository planRepository,
            EntitlementService entitlementService,
            MeterRegistry meterRegistry) {
        this.providerRegistry = providerRegistry;
        this.subscriptionRepository = subscriptionRepository;
        this.lifecycleService = lifecycleService;
        this.planRepository = planRepository;
        this.entitlementService = entitlementService;
        this.reconciliationFixCounter = Counter.builder("billing_reconciliation_fixes_total")
                .description("Number of subscription state fixes during reconciliation")
                .register(meterRegistry);
        this.reconciliationErrorCounter = Counter.builder("billing_reconciliation_errors_total")
                .description("Number of errors during reconciliation")
                .register(meterRegistry);
    }

    @SystemTenant
    @Scheduled(cron = "${billing.reconciliation.cron:0 0 */6 * * *}")
    @SchedulerLock(name = "billing_reconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void reconcile() {
        if (!entitlementService.isBillingEnabled()) return;

        for (BillingProvider provider : providerRegistry.all()) {
            if (!provider.supports(BillingCapability.MANAGED_SUBSCRIPTIONS)) {
                continue;
            }
            reconcileProvider(provider);
        }
    }

    private void reconcileProvider(BillingProvider provider) {
        List<BillingSubscription> subscriptions = subscriptionRepository
                .findReconcilable(provider.getProviderCode());

        if (subscriptions.isEmpty()) return;

        log.info("Reconciliation: checking {} subscriptions for provider {}",
                subscriptions.size(), provider.getProviderCode());

        int fixed = 0;
        int errors = 0;

        for (BillingSubscription sub : subscriptions) {
            try {
                boolean changed = reconcileSubscription(provider, sub);
                if (changed) fixed++;
            } catch (Exception e) {
                errors++;
                log.error("Reconciliation error for subscription {}: {}",
                        sub.getId(), e.getMessage());
            }
        }

        if (fixed > 0) {
            reconciliationFixCounter.increment(fixed);
            log.warn("Reconciliation: fixed {} drifted subscriptions for provider {}",
                    fixed, provider.getProviderCode());
        }
        if (errors > 0) {
            reconciliationErrorCounter.increment(errors);
        }
        log.info("Reconciliation complete for {}: checked={}, fixed={}, errors={}",
                provider.getProviderCode(), subscriptions.size(), fixed, errors);
    }

    private boolean reconcileSubscription(BillingProvider provider, BillingSubscription sub) {
        BillingProvider.ExternalSubscriptionState external =
                provider.fetchSubscriptionStatus(sub.getExternalSubscriptionId());

        if (external == null) {
            log.warn("Reconciliation: could not fetch external state for subscription {} (extId={})",
                    sub.getId(), sub.getExternalSubscriptionId());
            return false;
        }

        boolean changed = false;

        // 1. Status drift
        SubscriptionStatus expectedStatus = mapExternalStatus(external.status());
        if (expectedStatus != null && expectedStatus != sub.getStatus()) {
            log.warn("Reconciliation: subscription {} status drift: local={} external={} ({})",
                    sub.getId(), sub.getStatus(), expectedStatus, external.status());
            applyStatusFix(sub, expectedStatus, external);
            changed = true;
        }

        // 2. Period drift — only if external has newer period end
        if (external.periodEnd() != null && sub.getCurrentPeriodEnd() != null
                && external.periodEnd().isAfter(sub.getCurrentPeriodEnd())) {
            log.warn("Reconciliation: subscription {} period drift: local ends {} but external ends {}",
                    sub.getId(), sub.getCurrentPeriodEnd(), external.periodEnd());
            sub.setCurrentPeriodStart(external.periodStart());
            sub.setCurrentPeriodEnd(external.periodEnd());
            subscriptionRepository.save(sub);
            changed = true;
        }

        // 3. Plan drift
        if (external.planName() != null) {
            Plan currentPlan = sub.getPlan();
            if (currentPlan != null && !external.planName().equals(currentPlan.getName())) {
                planRepository.findByName(external.planName()).ifPresent(newPlan -> {
                    log.warn("Reconciliation: subscription {} plan drift: local={} external={}",
                            sub.getId(), currentPlan.getName(), external.planName());
                    lifecycleService.changePlan(sub.getId(), newPlan);
                });
                changed = true;
            }
        }

        return changed;
    }

    private void applyStatusFix(BillingSubscription sub,
                                 SubscriptionStatus targetStatus,
                                 BillingProvider.ExternalSubscriptionState external) {
        switch (targetStatus) {
            case ACTIVE -> {
                Instant start = external.periodStart() != null ? external.periodStart() : Instant.now();
                Instant end = external.periodEnd() != null ? external.periodEnd() : Instant.now();
                if (sub.getStatus() == SubscriptionStatus.PAST_DUE
                        || sub.getStatus() == SubscriptionStatus.GRACE_PERIOD) {
                    lifecycleService.activate(sub.getId(), start, end);
                } else {
                    lifecycleService.renew(sub.getId(), start, end);
                }
            }
            case PAST_DUE -> lifecycleService.markPastDue(sub.getId(),
                    "Reconciliation: external status is past_due");
            case CANCELLED -> lifecycleService.cancel(sub.getId(),
                    "Reconciliation: subscription cancelled externally");
            case SUSPENDED -> lifecycleService.suspend(sub.getId());
            default -> log.debug("Reconciliation: no action for target status {}", targetStatus);
        }
    }

    private SubscriptionStatus mapExternalStatus(String externalStatus) {
        if (externalStatus == null) return null;
        return STRIPE_STATUS_MAP.get(externalStatus.toLowerCase());
    }
}
