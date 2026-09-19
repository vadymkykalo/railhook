package com.webhook.platform.api.service.demo;

import com.webhook.platform.api.service.demo.DemoCatalog.DemoDestination;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoEndpoint;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoSource;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoSubscription;
import com.webhook.platform.api.service.demo.DemoHistory.AttemptRow;
import com.webhook.platform.api.service.demo.DemoHistory.DeliveryRow;
import com.webhook.platform.api.service.demo.DemoHistory.EventRow;
import com.webhook.platform.api.service.demo.DemoHistory.ForwardRow;
import com.webhook.platform.api.service.demo.DemoHistory.IncomingEventRow;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.common.demo.DemoTenant;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the public demo's organization in place and its history recent.
 *
 * <p>Two halves, both idempotent. The organization, its one member, project "Acme Shop", its
 * Endpoints, Subscriptions, Sources and Destinations are inserted with fixed ids and
 * {@code ON CONFLICT DO NOTHING}: seeding twice finds them there and changes nothing. The traffic
 * is replaced — the demo's Events, Deliveries, Attempts, Incoming Events and Forwards are deleted
 * and {@link DemoHistory} written again, ending at the current time — so the dashboard and the
 * analytics always show the last day rather than the day the server started.
 *
 * <p>JDBC rather than the entities: the history needs its own timestamps, which
 * {@code @CreationTimestamp} would overwrite, and fixed ids, which a generated id would not take.
 * Every row names the demo organization explicitly, which is also why this runs as the system
 * tenant: there is no request, and no tenant filter to apply to plain JDBC.
 *
 * <p>Only exists where {@code demo.enabled} is true. Where it is false, {@link DemoDataRemover}
 * takes its place and deletes whatever an earlier boot with the demo on left behind.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class DemoDataSeeder {

    /** "RAILDEMO": serialises seeding across API replicas that start together. */
    static final long ADVISORY_LOCK_KEY = 0x5241494c44454d4fL;

    /** Before any real sign-up could have happened, so no signup or activation window counts them. */
    private static final Timestamp PINNED_CREATED_AT = Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"));

    static final String DEMO_EMAIL = "visitor@demo.railhook.invalid";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final Clock clock;
    private final boolean billingEnabled;

    public DemoDataSeeder(JdbcTemplate jdbc,
                          PlatformTransactionManager transactionManager,
                          EncryptionKeyRegistry encryptionKeyRegistry,
                          Clock clock,
                          @Value("${billing.enabled:false}") boolean billingEnabled) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.clock = clock;
        this.billingEnabled = billingEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    @SystemTenant("seeds the demo organization at startup; there is no caller")
    public void seedOnStartup() {
        try {
            seed();
        } catch (RuntimeException e) {
            // A broken demo must not take the API down with it: the session endpoint answers 503
            // until a refresh succeeds.
            log.error("Could not seed the public demo: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "${demo.refresh-interval-minutes:60}",
            initialDelayString = "${demo.refresh-interval-minutes:60}", timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "demoDataRefresh", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @SystemTenant("regenerates the demo organization's history; scheduled, no caller")
    public void refresh() {
        seed();
    }

    /** Puts the demo in place and rewrites its history to end now. Safe to call any number of times. */
    @SystemTenant("writes the demo organization's rows by their fixed ids")
    public void seed() {
        Instant now = Instant.now(clock);
        DemoHistory history = DemoHistory.generate(now);
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
            seedStructure(now);
            replaceHistory(history);
        });
        log.info("Public demo seeded: {} events, {} deliveries, {} incoming events",
                history.events.size(), history.deliveries.size(), history.incomingEvents.size());
    }

    private void seedStructure(Instant now) {
        String planName = billingEnabled ? "free" : "self_hosted";
        jdbc.update("INSERT INTO organizations (id, name, plan_id, billing_status, created_at) "
                        + "SELECT ?, 'Acme Inc.', p.id, 'ACTIVE', ? FROM plans p WHERE p.name = ? "
                        + "ON CONFLICT (id) DO NOTHING",
                DemoTenant.ORGANIZATION_ID, PINNED_CREATED_AT, planName);
        Integer organizations = jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?",
                Integer.class, DemoTenant.ORGANIZATION_ID);
        if (organizations == null || organizations == 0) {
            throw new IllegalStateException("Plan '" + planName + "' not found; the demo organization needs one");
        }

        // No password: the demo person cannot sign in, only be handed a demo session. The
        // onboarding mails are marked sent, so the nudge scheduler never writes to them.
        Timestamp sent = Timestamp.from(now);
        jdbc.update("INSERT INTO users (id, email, full_name, password_hash, status, email_verified, "
                        + "failed_login_attempts, onboarding_welcome_sent_at, onboarding_nudge_sent_at, created_at) "
                        + "VALUES (?, ?, 'Demo visitor', NULL, 'ACTIVE', true, 0, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                DemoTenant.USER_ID, DEMO_EMAIL, sent, sent, PINNED_CREATED_AT);

        // Exactly one member, a Viewer — reasserted on every run rather than trusted, because the
        // demo's safety is argued from it.
        jdbc.update("INSERT INTO memberships (id, user_id, organization_id, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VIEWER', 'ACTIVE', ?, ?) ON CONFLICT (id) DO NOTHING",
                DemoCatalog.id(0x04), DemoTenant.USER_ID, DemoTenant.ORGANIZATION_ID, PINNED_CREATED_AT, PINNED_CREATED_AT);
        jdbc.update("UPDATE memberships SET role = 'VIEWER', status = 'ACTIVE' WHERE id = ? "
                + "AND (role <> 'VIEWER' OR status <> 'ACTIVE')", DemoCatalog.id(0x04));
        jdbc.update("DELETE FROM memberships WHERE organization_id = ? AND id <> ?",
                DemoTenant.ORGANIZATION_ID, DemoCatalog.id(0x04));

        jdbc.update("INSERT INTO projects (id, organization_id, name, description, created_at, updated_at) "
                        + "VALUES (?, ?, 'Acme Shop', ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                DemoTenant.PROJECT_ID, DemoTenant.ORGANIZATION_ID,
                "The storefront's order, payment and shipment events, and the Stripe and GitHub webhooks it receives.",
                PINNED_CREATED_AT, PINNED_CREATED_AT);

        for (DemoEndpoint endpoint : DemoCatalog.ENDPOINTS) {
            CryptoUtils.EncryptedData secret = encryptionKeyRegistry.encrypt("whsec_" + CryptoUtils.generateSecureToken(24));
            jdbc.update("INSERT INTO endpoints (id, organization_id, project_id, url, description, enabled, "
                            + "secret_encrypted, secret_iv, encryption_key_version, verification_status, "
                            + "verification_completed_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, true, ?, ?, ?, 'VERIFIED', ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    endpoint.id(), DemoTenant.ORGANIZATION_ID, DemoTenant.PROJECT_ID, endpoint.url(),
                    endpoint.description(), secret.getCiphertext(), secret.getIv(), secret.getKeyVersion(),
                    PINNED_CREATED_AT, PINNED_CREATED_AT, PINNED_CREATED_AT);
        }
        for (DemoSubscription subscription : DemoCatalog.SUBSCRIPTIONS) {
            jdbc.update("INSERT INTO subscriptions (id, organization_id, project_id, endpoint_id, event_type, enabled, "
                            + "max_attempts, retry_delays, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, true, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    subscription.id(), DemoTenant.ORGANIZATION_ID, DemoTenant.PROJECT_ID,
                    subscription.endpoint().id(), subscription.eventType(), subscription.maxAttempts(),
                    subscription.retryDelays(), PINNED_CREATED_AT, PINNED_CREATED_AT);
        }
        for (DemoSource source : DemoCatalog.SOURCES) {
            CryptoUtils.EncryptedData secret = encryptionKeyRegistry.encrypt(CryptoUtils.generateSecureToken(24));
            jdbc.update("INSERT INTO incoming_sources (id, organization_id, project_id, name, slug, provider_type, "
                            + "status, ingress_path_token, verification_mode, hmac_secret_encrypted, hmac_secret_iv, "
                            + "hmac_header_name, hmac_signature_prefix, encryption_key_version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 'PROVIDER', ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (id) DO NOTHING",
                    source.id(), DemoTenant.ORGANIZATION_ID, DemoTenant.PROJECT_ID, source.name(), source.slug(),
                    source.providerType(), CryptoUtils.generateSecureToken(32), secret.getCiphertext(), secret.getIv(),
                    source.hmacHeaderName(), source.hmacPrefix(), secret.getKeyVersion(),
                    PINNED_CREATED_AT, PINNED_CREATED_AT);
        }
        for (DemoDestination destination : DemoCatalog.DESTINATIONS) {
            jdbc.update("INSERT INTO incoming_destinations (id, organization_id, incoming_source_id, url, auth_type, "
                            + "enabled, max_attempts, timeout_seconds, retry_delays, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'NONE', true, 5, 30, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    destination.id(), DemoTenant.ORGANIZATION_ID, destination.source().id(), destination.url(),
                    DemoCatalog.INCOMING_DELAYS, PINNED_CREATED_AT, PINNED_CREATED_AT);
        }
    }

    /** The demo's traffic, as deleted: how many rows of each went. */
    record DeletedHistory(int forwards, int incomingEvents, int attempts, int deliveries, int events) {
    }

    /**
     * Deletes the demo organization's traffic, children first. Every statement names the demo
     * organization, and nothing else. The caller holds the transaction and the advisory lock.
     */
    static DeletedHistory deleteHistory(JdbcTemplate jdbc) {
        int forwards = jdbc.update("DELETE FROM incoming_forward_attempts WHERE organization_id = ?",
                DemoTenant.ORGANIZATION_ID);
        int incomingEvents = jdbc.update("DELETE FROM incoming_events WHERE organization_id = ?",
                DemoTenant.ORGANIZATION_ID);
        int attempts = jdbc.update("DELETE FROM delivery_attempts WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        int deliveries = jdbc.update("DELETE FROM deliveries WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        int events = jdbc.update("DELETE FROM events WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        return new DeletedHistory(forwards, incomingEvents, attempts, deliveries, events);
    }

    private void replaceHistory(DemoHistory history) {
        deleteHistory(jdbc);

        batch("INSERT INTO events (id, organization_id, project_id, event_type, payload, payload_compressed, created_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, false, ?)",
                history.events, (EventRow e) -> new Object[]{e.id(), DemoTenant.ORGANIZATION_ID, DemoTenant.PROJECT_ID,
                        e.eventType(), e.payload(), ts(e.createdAt())});

        batch("INSERT INTO deliveries (id, organization_id, event_id, endpoint_id, subscription_id, delivery_origin, "
                        + "status, attempt_count, max_attempts, ordering_enabled, timeout_seconds, retry_delays, "
                        + "next_retry_at, last_attempt_at, succeeded_at, failed_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'SUBSCRIPTION', ?, ?, ?, false, 30, ?, NULL, ?, ?, ?, ?, ?, 0)",
                history.deliveries, (DeliveryRow d) -> new Object[]{d.id(), DemoTenant.ORGANIZATION_ID, d.eventId(),
                        d.endpointId(), d.subscriptionId(), d.status(), d.attemptCount(), d.maxAttempts(),
                        d.retryDelays(), ts(d.lastAttemptAt()), ts(d.succeededAt()), ts(d.failedAt()),
                        ts(d.createdAt()), ts(d.lastAttemptAt())});

        batch("INSERT INTO delivery_attempts (id, organization_id, delivery_id, attempt_number, http_status_code, "
                        + "request_headers, request_body, response_headers, response_body, error_message, duration_ms, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?)",
                history.attempts, (AttemptRow a) -> new Object[]{a.id(), DemoTenant.ORGANIZATION_ID, a.deliveryId(),
                        a.attemptNumber(), a.httpStatus(), a.requestHeaders(), a.requestBody(), a.responseHeaders(),
                        a.responseBody(), a.errorMessage(), a.durationMs(), ts(a.createdAt())});

        batch("INSERT INTO incoming_events (id, organization_id, incoming_source_id, request_id, method, path, "
                        + "headers_json, body_raw, body_sha256, provider_event_id, content_type, client_ip, user_agent, "
                        + "verified, received_at) VALUES (?, ?, ?, ?, 'POST', ?, ?, ?, ?, ?, 'application/json', ?, ?, true, ?)",
                history.incomingEvents, (IncomingEventRow i) -> new Object[]{i.id(), DemoTenant.ORGANIZATION_ID,
                        i.sourceId(), i.requestId(), i.path(), i.headersJson(), i.body(), i.bodySha256(),
                        i.providerEventId(), i.clientIp(), i.userAgent(), ts(i.receivedAt())});

        batch("INSERT INTO incoming_forward_attempts (id, organization_id, incoming_event_id, destination_id, "
                        + "attempt_number, status, started_at, finished_at, request_headers_json, request_body_snippet, "
                        + "response_code, response_headers_json, response_body_snippet, error_message, next_retry_at, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                history.forwards, (ForwardRow f) -> new Object[]{f.id(), DemoTenant.ORGANIZATION_ID, f.incomingEventId(),
                        f.destinationId(), f.attemptNumber(), f.status(), ts(f.startedAt()), ts(f.finishedAt()),
                        f.requestHeaders(), f.requestBody(), f.responseCode(), f.responseHeaders(), f.responseBody(),
                        f.errorMessage(), ts(f.createdAt())});
    }

    private interface Row<T> {
        Object[] values(T row);
    }

    private <T> void batch(String sql, List<T> rows, Row<T> mapper) {
        List<Object[]> args = rows.stream().map(mapper::values).toList();
        for (int from = 0; from < args.size(); from += 500) {
            jdbc.batchUpdate(sql, args.subList(from, Math.min(from + 500, args.size())));
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
