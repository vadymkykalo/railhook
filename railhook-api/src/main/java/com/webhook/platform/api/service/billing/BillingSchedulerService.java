package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled jobs for billing lifecycle:
 * <ul>
 *   <li>Renew merchant-initiated subscriptions (WayForPay)</li>
 *   <li>Expire grace periods → suspend</li>
 *   <li>Apply scheduled plan changes</li>
 *   <li>Generate invoices for upcoming renewals</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingSchedulerService {

    private static final Duration GRACE_PERIOD_DURATION = Duration.ofDays(7);

    private final BillingSubscriptionRepository subscriptionRepository;
    private final BillingScheduledChangeRepository scheduledChangeRepository;
    private final BillingInvoiceRepository invoiceRepository;
    private final BillingPaymentRepository paymentRepository;
    private final PlanRepository planRepository;
    private final BillingProviderRegistry providerRegistry;
    private final SubscriptionLifecycleService lifecycleService;
    private final EntitlementService entitlementService;

    // ── Merchant-initiated renewals (WayForPay) ─────────────────────

    @SystemTenant
    @Scheduled(cron = "0 0 */1 * * *")
    @SchedulerLock(name = "billing_renewals", lockAtMostFor = "PT50M", lockAtLeastFor = "PT5M")
    public void processRenewals() {
        if (!entitlementService.isBillingEnabled()) return;

        Instant now = Instant.now();

        for (BillingProvider provider : providerRegistry.all()) {
            if (!provider.supports(BillingCapability.MERCHANT_RECURRING)) continue;

            List<BillingSubscription> due = subscriptionRepository
                    .findDueForRenewal(now, provider.getProviderCode());

            for (BillingSubscription sub : due) {
                try {
                    processRenewal(sub, provider);
                } catch (Exception e) {
                    log.error("Failed to renew subscription {}: {}", sub.getId(), e.getMessage(), e);
                }
            }

            if (!due.isEmpty()) {
                log.info("Processed {} renewals for provider {}", due.size(), provider.getProviderCode());
            }
        }
    }

    private void processRenewal(BillingSubscription sub, BillingProvider provider) {
        if (sub.getRecurringTokenEncrypted() == null) {
            log.warn("Subscription {} has no recurring token, marking past due", sub.getId());
            lifecycleService.markPastDue(sub.getId(), "No recurring token");
            return;
        }

        long amountCents = sub.getBillingInterval() == BillingInterval.YEARLY
                ? sub.getPlan().getPriceYearlyCents()
                : sub.getPlan().getPriceMonthlyCents();
        String orderRef = "railhook_renew_" + sub.getId() + "_" + System.currentTimeMillis();

        // Create invoice
        BillingInvoice invoice = BillingInvoice.builder()
                .organizationId(sub.getOrganizationId())
                .subscriptionId(sub.getId())
                .providerCode(provider.getProviderCode())
                .status(InvoiceStatus.OPEN)
                .subtotalCents(amountCents)
                .totalCents(amountCents)
                .currency(sub.getCurrency())
                .periodStart(sub.getCurrentPeriodEnd())
                .periodEnd(sub.getCurrentPeriodEnd().atZone(java.time.ZoneOffset.UTC)
                        .plus(sub.getBillingInterval().getPeriod()).toInstant())
                .dueDate(Instant.now())
                .build();
        invoice = invoiceRepository.save(invoice);

        // Charge
        BillingProvider.ChargeResult result = provider.chargeRecurring(
                new BillingProvider.RecurringChargeRequest(
                        sub.getOrganizationId(),
                        sub.getRecurringTokenEncrypted(),
                        amountCents,
                        sub.getCurrency(),
                        orderRef,
                        "Railhook " + sub.getPlan().getDisplayName() + " renewal"
                ));

        // Record payment
        BillingPayment payment = BillingPayment.builder()
                .invoiceId(invoice.getId())
                .organizationId(sub.getOrganizationId())
                .subscriptionId(sub.getId())
                .providerCode(provider.getProviderCode())
                .externalPaymentId(result.externalPaymentId())
                .status(result.success() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED)
                .amountCents(amountCents)
                .currency(sub.getCurrency())
                .cardLast4(result.cardLast4())
                .cardBrand(result.cardBrand())
                .failureCode(result.failureCode())
                .failureMessage(result.failureMessage())
                .build();
        paymentRepository.save(payment);

        if (result.success()) {
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setPaidAt(Instant.now());
            invoiceRepository.save(invoice);

            Instant newStart = sub.getCurrentPeriodEnd();
            Instant newEnd = newStart.atZone(java.time.ZoneOffset.UTC)
                    .plus(sub.getBillingInterval().getPeriod()).toInstant();
            lifecycleService.renew(sub.getId(), newStart, newEnd);
        } else {
            invoice.setStatus(InvoiceStatus.PAST_DUE);
            invoiceRepository.save(invoice);
            lifecycleService.markPastDue(sub.getId(),
                    "Payment failed: " + result.failureCode() + " — " + result.failureMessage());
        }
    }

    // ── Grace period expiry ─────────────────────────────────────────

    @SystemTenant
    @Scheduled(cron = "0 30 */1 * * *")
    @SchedulerLock(name = "billing_grace_expiry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2M")
    public void processGracePeriodExpiry() {
        if (!entitlementService.isBillingEnabled()) return;

        Instant graceCutoff = Instant.now().minus(GRACE_PERIOD_DURATION);
        List<BillingSubscription> expired = subscriptionRepository.findGracePeriodExpired(graceCutoff);

        for (BillingSubscription sub : expired) {
            try {
                lifecycleService.suspend(sub.getId());
            } catch (Exception e) {
                log.error("Failed to suspend subscription {}: {}", sub.getId(), e.getMessage(), e);
            }
        }

        if (!expired.isEmpty()) {
            log.info("Suspended {} subscriptions after grace period expiry", expired.size());
        }
    }

    // ── PAST_DUE → GRACE_PERIOD transition ──────────────────────────

    @SystemTenant
    @Scheduled(cron = "0 15 */2 * * *")
    @SchedulerLock(name = "billing_past_due_to_grace", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2M")
    public void processPastDueToGrace() {
        if (!entitlementService.isBillingEnabled()) return;

        Instant threshold = Instant.now().minus(Duration.ofDays(3));
        List<BillingSubscription> pastDue = subscriptionRepository
                .findExpiredByStatus(SubscriptionStatus.PAST_DUE, threshold);

        for (BillingSubscription sub : pastDue) {
            try {
                lifecycleService.startGracePeriod(sub.getId());
            } catch (Exception e) {
                log.error("Failed to start grace for subscription {}: {}", sub.getId(), e.getMessage(), e);
            }
        }

        if (!pastDue.isEmpty()) {
            log.info("Moved {} subscriptions from PAST_DUE to GRACE_PERIOD", pastDue.size());
        }
    }

    // ── Scheduled plan changes ──────────────────────────────────────

    @SystemTenant
    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "billing_scheduled_changes", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2M")
    @Transactional
    public void applyScheduledChanges() {
        if (!entitlementService.isBillingEnabled()) return;

        Instant now = Instant.now();
        List<BillingScheduledChange> ready = scheduledChangeRepository.findReadyToApply(now);

        for (BillingScheduledChange change : ready) {
            try {
                Plan newPlan = planRepository.findById(change.getToPlanId())
                        .orElseThrow(() -> new RuntimeException("Plan not found: " + change.getToPlanId()));

                if (change.getChangeType() == ScheduledChangeType.CANCELLATION) {
                    lifecycleService.cancel(change.getSubscriptionId(), "Scheduled cancellation");
                } else {
                    lifecycleService.changePlan(change.getSubscriptionId(), newPlan);
                }

                change.setStatus(ScheduledChangeStatus.APPLIED);
                change.setAppliedAt(now);
                scheduledChangeRepository.save(change);

                log.info("Applied scheduled change {}: sub={} → plan={}",
                        change.getId(), change.getSubscriptionId(), newPlan.getName());
            } catch (Exception e) {
                log.error("Failed to apply scheduled change {}: {}", change.getId(), e.getMessage(), e);
            }
        }

        if (!ready.isEmpty()) {
            log.info("Applied {} scheduled plan changes", ready.size());
        }
    }
}
