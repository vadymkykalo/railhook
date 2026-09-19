package com.webhook.platform.api.service.demo;

import com.webhook.platform.api.service.demo.DemoDataSeeder.DeletedHistory;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.common.demo.DemoTenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the public demo's rows from an installation that no longer runs the demo.
 *
 * <p>The counterpart to {@link DemoDataSeeder}, and present exactly where the seeder is not:
 * with {@code demo.enabled} false, each API start removes whatever an earlier boot with the demo
 * on left behind. Where there is nothing — every installation that never enabled the demo — it
 * reads two rows and returns.
 *
 * <p>Everything is deleted by the fixed ids in {@link DemoTenant} and nothing else: the history
 * through the seeder's own statements, then the structure the seeder inserted, then the
 * organization, whose foreign keys cascade to anything else it owns. The demo person goes last,
 * and only when no membership anywhere still names them. One transaction, under the seeder's
 * advisory lock, so replicas starting together remove it once and the rest find nothing.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "demo.enabled", havingValue = "false", matchIfMissing = true)
public class DemoDataRemover {

    /** What one run removed. {@code organization} and {@code user} say whether those rows went. */
    public record Removed(boolean organization, boolean user, int events, int deliveries, int attempts,
                          int incomingEvents, int forwards) {

        static final Removed NOTHING = new Removed(false, false, 0, 0, 0, 0, 0);

        public boolean anything() {
            return organization || user || events > 0 || deliveries > 0 || attempts > 0
                    || incomingEvents > 0 || forwards > 0;
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DemoDataRemover(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    @SystemTenant("removes the disabled demo's rows at startup; there is no caller")
    public void removeOnStartup() {
        try {
            removeIfPresent();
        } catch (RuntimeException e) {
            // Leftover demo rows are harmless — nothing can sign in to them — so failing to remove
            // them must not take the API down. The next start tries again.
            log.error("Could not remove the disabled demo's data: {}", e.getMessage(), e);
        }
    }

    /** Deletes the demo's rows if any are there. Safe to call any number of times. */
    @SystemTenant("deletes the demo organization's rows by their fixed ids")
    public Removed removeIfPresent() {
        if (!demoRowsExist()) {
            return Removed.NOTHING;
        }
        Removed removed = transactions.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(" + DemoDataSeeder.ADVISORY_LOCK_KEY + ")");
            return remove();
        });
        if (removed != null && removed.anything()) {
            log.info("Demo disabled, its data removed: organization {}, user {}, {} events, {} deliveries, "
                            + "{} attempts, {} incoming events, {} forwards",
                    removed.organization() ? "deleted" : "absent", removed.user() ? "deleted" : "kept",
                    removed.events(), removed.deliveries(), removed.attempts(), removed.incomingEvents(),
                    removed.forwards());
        }
        return removed == null ? Removed.NOTHING : removed;
    }

    private boolean demoRowsExist() {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM organizations WHERE id = ?) "
                        + "OR EXISTS (SELECT 1 FROM users WHERE id = ? AND email = ?)",
                Boolean.class, DemoTenant.ORGANIZATION_ID, DemoTenant.USER_ID, DemoDataSeeder.DEMO_EMAIL);
        return Boolean.TRUE.equals(exists);
    }

    private Removed remove() {
        // The seeder writes no outbox rows; a message here would be for a Delivery about to go.
        jdbc.update("DELETE FROM outbox_messages WHERE project_id = ?", DemoTenant.PROJECT_ID);
        DeletedHistory history = DemoDataSeeder.deleteHistory(jdbc);

        // The structure the seeder inserted, children first; the organization's cascades would
        // reach all of it, but an explicit order does not depend on every foreign key saying so.
        jdbc.update("DELETE FROM incoming_destinations WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM incoming_sources WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM subscriptions WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM endpoints WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM consumers WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM memberships WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM projects WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        boolean organization = jdbc.update("DELETE FROM organizations WHERE id = ?", DemoTenant.ORGANIZATION_ID) > 0;

        // The demo address is on a reserved domain nobody can sign up with, and the row is matched
        // on it as well as the id. A membership elsewhere means someone made this row part of a
        // real organization; it stays.
        boolean user = jdbc.update("DELETE FROM users WHERE id = ? AND email = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM memberships WHERE user_id = ?)",
                DemoTenant.USER_ID, DemoDataSeeder.DEMO_EMAIL, DemoTenant.USER_ID) > 0;

        return new Removed(organization, user, history.events(), history.deliveries(), history.attempts(),
                history.incomingEvents(), history.forwards());
    }
}
