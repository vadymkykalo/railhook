package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.tenancy.SystemTenant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job that enforces per-plan retention limits.
 * <p>
 * Runs daily at 03:00 UTC. For each organization whose plan has a finite
 * {@code max_retention_days}, deletes events (and their cascaded deliveries /
 * delivery_attempts) that are older than the cutoff.
 * <p>
 * Deletions happen in batches to avoid long locks. Self-hosted plans
 * ({@code max_retention_days = -1}) are skipped (unlimited retention).
 * <p>
 * When {@code billing.enabled=false}, the scheduler is a no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionCleanupScheduler {

    private static final int BATCH_SIZE = 1000;

    private final EntitlementService entitlementService;

    @PersistenceContext
    private EntityManager em;

    @SystemTenant
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "retention_cleanup", lockAtMostFor = "PT55M", lockAtLeastFor = "PT5M")
    @Transactional
    public void cleanup() {
        if (!entitlementService.isBillingEnabled()) return;

        // Step 1: delete delivery_attempts for expired events
        int totalAttempts = deleteInBatches("""
            DELETE FROM delivery_attempts
            WHERE id IN (
                SELECT da.id FROM delivery_attempts da
                JOIN deliveries d ON da.delivery_id = d.id
                JOIN events e ON d.event_id = e.id
                JOIN projects p ON e.project_id = p.id
                JOIN organizations o ON p.organization_id = o.id
                JOIN plans pl ON o.plan_id = pl.id
                WHERE pl.max_retention_days > 0
                  AND e.created_at < NOW() - (pl.max_retention_days || ' days')::interval
                LIMIT :batchSize
            )
            """);

        // Step 2: delete deliveries for expired events
        int totalDeliveries = deleteInBatches("""
            DELETE FROM deliveries
            WHERE id IN (
                SELECT d.id FROM deliveries d
                JOIN events e ON d.event_id = e.id
                JOIN projects p ON e.project_id = p.id
                JOIN organizations o ON p.organization_id = o.id
                JOIN plans pl ON o.plan_id = pl.id
                WHERE pl.max_retention_days > 0
                  AND e.created_at < NOW() - (pl.max_retention_days || ' days')::interval
                  AND NOT EXISTS (
                      SELECT 1 FROM delivery_attempts da WHERE da.delivery_id = d.id
                  )
                LIMIT :batchSize
            )
            """);

        // Step 3: delete expired events (no remaining deliveries)
        int totalEvents = deleteInBatches("""
            DELETE FROM events
            WHERE id IN (
                SELECT e.id FROM events e
                JOIN projects p ON e.project_id = p.id
                JOIN organizations o ON p.organization_id = o.id
                JOIN plans pl ON o.plan_id = pl.id
                WHERE pl.max_retention_days > 0
                  AND e.created_at < NOW() - (pl.max_retention_days || ' days')::interval
                  AND NOT EXISTS (
                      SELECT 1 FROM deliveries d WHERE d.event_id = e.id
                  )
                LIMIT :batchSize
            )
            """);

        if (totalAttempts + totalDeliveries + totalEvents > 0) {
            log.info("Retention cleanup: deleted {} attempts, {} deliveries, {} events",
                    totalAttempts, totalDeliveries, totalEvents);
        }
    }

    private int deleteInBatches(String sql) {
        int total = 0;
        int deleted;
        do {
            deleted = em.createNativeQuery(sql)
                    .setParameter("batchSize", BATCH_SIZE)
                    .executeUpdate();
            total += deleted;
            if (deleted > 0) {
                em.flush();
                em.clear();
            }
        } while (deleted >= BATCH_SIZE);
        return total;
    }
}
