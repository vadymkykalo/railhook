package com.webhook.platform.api;

import com.webhook.platform.api.service.PartitionMaintenanceService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises PartitionMaintenanceService against a real Postgres (Testcontainers),
 * which is also where the V052/V053 partitioning migrations get their only real
 * verification in this repo's test suite: AbstractIntegrationTest boots a full Spring
 * context, which runs Flyway, which applies V052/V053 — so a broken partitioning
 * migration fails every integration test class, not just this one. These tests then
 * check the actual partition-drop / partition-create behavior PartitionMaintenanceService
 * is responsible for.
 * <p>
 * All methods here share one Testcontainers Postgres database
 * (@DirtiesContext(AFTER_CLASS) on AbstractIntegrationTest), so this class is ordered
 * explicitly: {@link #dropExpiredPartitionsRemovesOnlyPartitionsFullyPastRetention()}
 * drops the real, migration-created legacy partition to prove the mechanism end to end
 * (proving a retention run actually drops a partition end to end), and every other test that assumes that partition still
 * exists is ordered before it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartitionMaintenanceServiceIntegrationTest extends AbstractIntegrationTest {

    /**
     * Fixture tenant for rows inserted straight through JDBC.
     *
     * <p>These fixtures bypass the entity mapping (and the FK checks, via
     * {@code session_replication_role = replica}) that would normally stamp
     * {@code organization_id}, so they name one themselves. The value only has to be non-null and
     * consistent — nothing here asserts on tenant confinement.
     */
    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    @Autowired
    private PartitionMaintenanceService partitionMaintenanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void migrationCreatesMonthlyPartitionsPlusLegacyAndDefault() {
        List<String> partitions = childPartitionNames("delivery_attempts");

        assertTrue(partitions.contains("delivery_attempts_legacy"),
                "pre-cutover history should be attached as delivery_attempts_legacy");
        assertTrue(partitions.contains("delivery_attempts_default"),
                "a DEFAULT partition must exist so out-of-range inserts don't fail outright");
        assertTrue(partitions.stream().anyMatch(p -> p.matches("delivery_attempts_y\\d{4}_m\\d{2}")),
                "at least one dated monthly partition should exist from the migration's initial seed");
    }

    @Test
    @Order(2)
    void migrationCreatesWeeklyPartitionsPlusLegacyAndDefault() {
        List<String> partitions = childPartitionNames("tunnel_request_log");

        assertTrue(partitions.contains("tunnel_request_log_legacy"));
        assertTrue(partitions.contains("tunnel_request_log_default"));
        assertTrue(partitions.stream().anyMatch(p -> p.matches("tunnel_request_log_y\\d{4}_w\\d{2}")));
    }

    @Test
    @Order(3)
    void rowsRouteToTheirCorrectPartitionByCreatedAt() {
        UUID deliveryId = createDelivery();

        // Well before the current month's cutover -> lands in the legacy partition.
        insertDeliveryAttempt(deliveryId, 1, Instant.now().minus(400, ChronoUnit.DAYS));
        // Now -> lands in the current-month partition.
        insertDeliveryAttempt(deliveryId, 2, Instant.now());
        // Far beyond the lookahead window seeded by the migration -> DEFAULT partition.
        insertDeliveryAttempt(deliveryId, 3, Instant.now().plus(1000, ChronoUnit.DAYS));

        Long legacyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempts_legacy WHERE delivery_id = ?", Long.class, deliveryId);
        Long defaultCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempts_default WHERE delivery_id = ?", Long.class, deliveryId);
        Long totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempts WHERE delivery_id = ?", Long.class, deliveryId);

        assertEquals(1L, legacyCount, "the far-past row should have landed in the legacy partition");
        assertEquals(1L, defaultCount, "the far-future row should have landed in the DEFAULT partition");
        assertEquals(3L, totalCount, "querying the parent table must transparently see all partitions");
    }

    @Test
    @Order(4)
    void ensureFutureMonthlyPartitionsExtendsTheWindowIdempotently() {
        partitionMaintenanceService.ensureFutureMonthlyPartitions("delivery_attempts", 6);
        List<String> afterFirstRun = childPartitionNames("delivery_attempts");
        long datedPartitions = afterFirstRun.stream().filter(p -> p.matches("delivery_attempts_y\\d{4}_m\\d{2}")).count();
        assertTrue(datedPartitions >= 7, "current month + 6 lookahead months should all exist");

        // Re-running with the same (or a smaller) lookahead must not error — CREATE TABLE
        // IF NOT EXISTS makes this idempotent.
        partitionMaintenanceService.ensureFutureMonthlyPartitions("delivery_attempts", 6);
        List<String> afterSecondRun = childPartitionNames("delivery_attempts");
        assertEquals(afterFirstRun.size(), afterSecondRun.size(), "re-running maintenance must not create duplicates");
    }

    @Test
    @Order(5)
    void dropExpiredPartitionsRemovesOnlyPartitionsFullyPastRetention() {
        // Ordered last (@Order(5)): this drops the real, migration-created legacy
        // partition, which every earlier test in this class relies on still existing.
        UUID deliveryId = createDelivery();
        insertDeliveryAttempt(deliveryId, 1, Instant.now().minus(400, ChronoUnit.DAYS)); // -> legacy
        insertDeliveryAttempt(deliveryId, 2, Instant.now());                              // -> current month

        // retentionDays = 0: the legacy partition's upper bound (start of this month) is
        // already in the past, so it's fully expired. The current-month partition's upper
        // bound is next month, still in the future, so it must survive.
        int dropped = partitionMaintenanceService.dropExpiredPartitions("delivery_attempts", 0);
        assertTrue(dropped >= 1, "expected at least the legacy partition to be dropped");

        List<String> remaining = childPartitionNames("delivery_attempts");
        assertFalse(remaining.contains("delivery_attempts_legacy"), "legacy partition should have been dropped");
        assertTrue(remaining.stream().anyMatch(p -> p.matches("delivery_attempts_y\\d{4}_m\\d{2}")),
                "the current/future dated partitions must not be touched");

        Long remainingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempts WHERE delivery_id = ?", Long.class, deliveryId);
        assertEquals(1L, remainingRows, "only the row that was in the dropped partition should be gone");
    }

    private List<String> childPartitionNames(String parentTable) {
        return jdbcTemplate.queryForList(
                """
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = ?::regclass
                """,
                String.class, parentTable);
    }

    private UUID createDelivery() {
        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.execute("SET session_replication_role = replica");
        jdbcTemplate.update(
                "INSERT INTO deliveries (id, event_id, endpoint_id, subscription_id, organization_id, status, attempt_count, max_attempts, ordering_enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                deliveryId, eventId, endpointId, subscriptionId, FIXTURE_ORG, "PENDING", 0, 5, false, now, now);
        jdbcTemplate.execute("SET session_replication_role = DEFAULT");
        return deliveryId;
    }

    private void insertDeliveryAttempt(UUID deliveryId, int attemptNumber, Instant createdAt) {
        jdbcTemplate.execute("SET session_replication_role = replica");
        jdbcTemplate.update(
                "INSERT INTO delivery_attempts (id, delivery_id, organization_id, attempt_number, http_status_code, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), deliveryId, FIXTURE_ORG, attemptNumber, 200, 100, Timestamp.from(createdAt));
        jdbcTemplate.execute("SET session_replication_role = DEFAULT");
    }
}
