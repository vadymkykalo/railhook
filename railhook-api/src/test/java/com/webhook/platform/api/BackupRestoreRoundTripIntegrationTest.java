package com.webhook.platform.api;

import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a backup can be restored, on the rows where that is not obvious.
 *
 * <p>{@code BackupFlagParityTest} already checks that the three places which run {@code pg_dump}
 * — the Makefile script, the Compose sidecar, the Helm CronJob — pass the same flags. That is
 * worth having and it is not this: it compares two strings, and says nothing about whether the
 * output is restorable or whether what comes back still works.
 *
 * <p>Three things here are not obvious and are what an operator finds out at the worst moment:
 *
 * <ul>
 *   <li>Secrets are encrypted with a key that lives in {@code WEBHOOK_ENCRYPTION_KEY}, outside
 *       the database. A dump without that key restores unreadable columns — which is why the
 *       installer says to keep {@code .env} with it, and why this asserts the round-tripped
 *       ciphertext still decrypts rather than merely that the bytes came back.</li>
 *   <li>A Delivery caught mid-flight restores as {@code PROCESSING} holding a {@code claim_token}
 *       whose worker no longer exists. Nothing in Kafka will drive it, so whether it ever moves
 *       again depends entirely on the stuck sweep finding it.</li>
 *   <li>An Outbox row restores {@code PENDING}, which is what makes the accepted-but-unannounced
 *       Event recoverable at all.</li>
 * </ul>
 *
 * <p>Restores into a <em>separate database on the same server</em> rather than over the source.
 * The Postgres container is shared by every integration test in the JVM; dropping its schema to
 * prove a point would be a destructive check run outside an isolated environment, and the
 * round-trip is proved either way.
 */
class BackupRestoreRoundTripIntegrationTest extends AbstractIntegrationTest {

    /** The same three flags db-backup.sh, the Compose sidecar and the chart's CronJob all use. */
    private static final String[] DUMP_FLAGS = {"-Fc", "--no-owner", "--no-privileges"};

    private static final String RESTORED_DB = "restore_check";

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private OutboxMessageRepository outboxMessageRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private EncryptionKeyRegistry encryptionKeyRegistry;

    @Test
    @DisplayName("a dump taken mid-flight restores with its secrets readable and its work recoverable")
    void roundTrip() throws Exception {
        String secret = "whsec_" + UUID.randomUUID();
        CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(secret);

        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        Organization org = organizationRepository.save(
                Organization.builder().name("Restore " + UUID.randomUUID()).plan(plan).build());
        Project project = projectRepository.save(Project.builder()
                .organizationId(org.getId()).name("Payments").build());
        Endpoint endpoint = endpointRepository.save(Endpoint.builder()
                .organizationId(org.getId()).projectId(project.getId())
                .url("https://receiver.example.test/hook")
                .secretEncrypted(encrypted.getCiphertext())
                .secretIv(encrypted.getIv())
                .encryptionKeyVersion(encrypted.getKeyVersion())
                .build());
        Event event = eventRepository.save(Event.builder()
                .organizationId(org.getId()).projectId(project.getId())
                .eventType("payment.succeeded").payload("{\"amount\":1000}").build());

        // Caught mid-attempt: PROCESSING, holding a fence token, with no worker left to finish it.
        UUID fence = UUID.randomUUID();
        Delivery inFlight = deliveryRepository.save(Delivery.builder()
                .organizationId(org.getId()).eventId(event.getId()).endpointId(endpoint.getId())
                .status(DeliveryStatus.PROCESSING)
                .claimToken(fence)
                .lastAttemptAt(Instant.now().minusSeconds(3600))
                .build());

        OutboxMessage unannounced = outboxMessageRepository.save(OutboxMessage.builder()
                .aggregateType("Event").aggregateId(event.getId())
                .eventType("payment.succeeded").payload("{}")
                .kafkaTopic("deliveries.dispatch").kafkaKey(endpoint.getId().toString())
                .projectId(project.getId())
                .status(OutboxStatus.PENDING).retryCount(0)
                .build());

        dumpAndRestore();

        try (Connection restored = DriverManager.getConnection(
                jdbcUrlFor(RESTORED_DB), postgres.getUsername(), postgres.getPassword())) {

            String[] columns = readEndpointSecret(restored, endpoint.getId());
            assertThat(columns[0]).isEqualTo(encrypted.getCiphertext());
            assertThat(encryptionKeyRegistry.decryptWithFallback(
                    columns[0], columns[1], Integer.parseInt(columns[2])))
                    .as("the dump carries ciphertext; the key that reads it lives in the environment, "
                            + "so a backup without .env restores columns nobody can open")
                    .isEqualTo(secret);

            assertThat(readOne(restored,
                    "SELECT status FROM deliveries WHERE id = ?", inFlight.getId()))
                    .isEqualTo("PROCESSING");
            assertThat(readOne(restored,
                    "SELECT claim_token FROM deliveries WHERE id = ?", inFlight.getId()))
                    .as("the fence survives, which is what stops a zombie writing over the recovery")
                    .isEqualTo(fence.toString());

            assertThat(readOne(restored,
                    "SELECT status FROM outbox_messages WHERE id = ?", unannounced.getId()))
                    .as("an accepted Event that was never announced is still announceable")
                    .isEqualTo("PENDING");

            // The stuck sweep is the only thing that will ever move that delivery again: Kafka
            // knows nothing about a database that went back in time.
            try (Statement sweep = restored.createStatement()) {
                int recovered = sweep.executeUpdate(
                        "UPDATE deliveries SET status = 'PENDING', claim_token = NULL, next_retry_at = now() "
                                + "WHERE status = 'PROCESSING' AND last_attempt_at < now() - interval '5 minutes'");
                assertThat(recovered)
                        .as("a restore leaves in-flight work claimed by a worker that no longer exists")
                        .isGreaterThanOrEqualTo(1);
            }
            assertThat(readOne(restored,
                    "SELECT status FROM deliveries WHERE id = ?", inFlight.getId()))
                    .isEqualTo("PENDING");
        }
    }

    private void dumpAndRestore() throws Exception {
        String[] dump = new String[]{"pg_dump", "-U", postgres.getUsername(),
                "-d", postgres.getDatabaseName(), "-f", "/tmp/roundtrip.dump"};
        String[] withFlags = new String[dump.length + DUMP_FLAGS.length];
        System.arraycopy(dump, 0, withFlags, 0, dump.length);
        System.arraycopy(DUMP_FLAGS, 0, withFlags, dump.length, DUMP_FLAGS.length);

        succeed(postgres.execInContainer(withFlags), "pg_dump");
        succeed(postgres.execInContainer("dropdb", "-U", postgres.getUsername(),
                "--if-exists", RESTORED_DB), "dropdb");
        succeed(postgres.execInContainer("createdb", "-U", postgres.getUsername(), RESTORED_DB), "createdb");
        succeed(postgres.execInContainer("pg_restore", "-U", postgres.getUsername(),
                "-d", RESTORED_DB, "--no-owner", "--no-privileges", "/tmp/roundtrip.dump"), "pg_restore");
    }

    private static void succeed(Container.ExecResult result, String what) {
        assertThat(result.getExitCode())
                .as("%s failed: %s%s", what, result.getStdout(), result.getStderr())
                .isZero();
    }

    private String jdbcUrlFor(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + database;
    }

    private String[] readEndpointSecret(Connection connection, UUID endpointId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT secret_encrypted, secret_iv, encryption_key_version FROM endpoints WHERE id = ?")) {
            statement.setObject(1, endpointId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the endpoint is in the restored database").isTrue();
                return new String[]{rows.getString(1), rows.getString(2), rows.getString(3)};
            }
        }
    }

    private String readOne(Connection connection, String sql, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("row present in the restored database: %s", sql).isTrue();
                return rows.getString(1);
            }
        }
    }
}
