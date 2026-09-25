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

/** Deletes only by the fixed demo ids, under the seeder's advisory lock so replicas remove it once. */
@Slf4j
@Service
@ConditionalOnProperty(name = "demo.enabled", havingValue = "false", matchIfMissing = true)
public class DemoDataRemover {

    public record Removed(boolean organization, boolean user, int events, int deliveries, int attempts,
                          int incomingEvents, int forwards, int workflowExecutions, int transformations) {

        static final Removed NOTHING = new Removed(false, false, 0, 0, 0, 0, 0, 0, 0);

        public boolean anything() {
            return organization || user || events > 0 || deliveries > 0 || attempts > 0
                    || incomingEvents > 0 || forwards > 0 || workflowExecutions > 0 || transformations > 0;
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
            // Leftover demo rows are harmless, so this must not stop the API from starting.
            log.error("Could not remove the disabled demo's data: {}", e.getMessage(), e);
        }
    }

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
                            + "{} attempts, {} incoming events, {} forwards, {} workflow runs, {} transformations",
                    removed.organization() ? "deleted" : "absent", removed.user() ? "deleted" : "kept",
                    removed.events(), removed.deliveries(), removed.attempts(), removed.incomingEvents(),
                    removed.forwards(), removed.workflowExecutions(), removed.transformations());
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
        jdbc.update("DELETE FROM outbox_messages WHERE project_id = ?", DemoTenant.PROJECT_ID);
        DeletedHistory history = DemoDataSeeder.deleteHistory(jdbc);

        // Children first, explicitly, rather than relying on every foreign key cascading.
        jdbc.update("DELETE FROM workflows WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM incoming_destinations WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM incoming_sources WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM subscriptions WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        int transformations = jdbc.update("DELETE FROM transformations WHERE organization_id = ?",
                DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM endpoints WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM memberships WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM projects WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        boolean organization = jdbc.update("DELETE FROM organizations WHERE id = ?", DemoTenant.ORGANIZATION_ID) > 0;

        // A membership elsewhere means someone made this user part of a real organization; keep it.
        boolean user = jdbc.update("DELETE FROM users WHERE id = ? AND email = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM memberships WHERE user_id = ?)",
                DemoTenant.USER_ID, DemoDataSeeder.DEMO_EMAIL, DemoTenant.USER_ID) > 0;

        return new Removed(organization, user, history.events(), history.deliveries(), history.attempts(),
                history.incomingEvents(), history.forwards(), history.workflowExecutions(), transformations);
    }
}
