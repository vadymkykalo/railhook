package com.webhook.platform.api.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// CREATE INDEX without CONCURRENTLY blocks writes to its table for the whole build.
@Tag("ratchet")
class MigrationIndexLockingTest {

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    // Tables that grow without bound; configuration tables are absent on purpose.
    private static final Set<String> UNBOUNDED_TABLES = Set.of(
            "events", "deliveries", "delivery_attempts",
            "incoming_events", "incoming_forward_attempts",
            "outbox_messages", "tunnel_request_log", "audit_log", "usage_daily");

    // Frozen: shipped migrations cannot be edited, so this set must not grow.
    private static final Set<String> SHIPPED_WITH_BLOCKING_INDEXES = new TreeSet<>(Set.of(
            // V001 and V002 run against an empty database by definition.
            "V001__initial_schema.sql",
            "V002__audit_log.sql",
            "V005__incoming_webhooks.sql",
            "V006__incoming_webhooks_highload.sql",
            "V011__deterministic_replay.sql",
            "V013__replay_sessions.sql",
            "V015__delivery_dashboard_indexes.sql",
            "V016__incoming_event_dedup.sql",
            "V018__replay_unique_constraint.sql",
            "V019__highload_partial_indexes.sql",
            "V020__alerts_and_usage.sql",
            "V022__highload_exists_indexes.sql",
            "V023__transformations.sql",
            "V032__event_payload_compression.sql",
            "V035__delivery_attempts_cleanup_index.sql",
            "V041__tunnel_request_log.sql",
            "V045__p0_delivery_fixes.sql",
            "V046__p1_noisy_neighbor_fixes.sql",
            "V047__p1_outbox_kafka_key_index.sql",
            "V048__outbox_correlation_id.sql",
            "V052__partition_delivery_attempts.sql",
            "V053__partition_tunnel_request_log.sql",
            "V057__replay_deliveries_unique.sql",
            "V064__incoming_forward_replay_session.sql"));

    private static final Pattern CREATE_INDEX = Pattern.compile(
            "CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(CONCURRENTLY\\s+)?(IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "[\\w.\"]+\\s+ON\\s+[\\w.\"]*?(\\w+)\\s*[\\s(]",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("a new migration does not block writes to build an index on an unbounded table")
    void newIndexesOnBigTablesAreConcurrent() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path migration : migrations()) {
            String name = migration.getFileName().toString();
            if (SHIPPED_WITH_BLOCKING_INDEXES.contains(name)) {
                continue;
            }
            String sql = stripComments(read(migration));
            Matcher m = CREATE_INDEX.matcher(sql);
            while (m.find()) {
                boolean concurrent = m.group(2) != null;
                String table = m.group(4).toLowerCase();
                if (!concurrent && UNBOUNDED_TABLES.contains(table)) {
                    offenders.add(name + " → " + table);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "These build an index on an unbounded table without CONCURRENTLY, so applying them "
                        + "blocks every write to that table until the index is finished — on an "
                        + "installation with real history, an outage for the length of the build:\n  "
                        + String.join("\n  ", offenders)
                        + "\n\nUse CREATE INDEX CONCURRENTLY, and add `-- flyway:executeInTransaction=false` "
                        + "as the first line: PostgreSQL refuses CONCURRENTLY inside a transaction and Flyway "
                        + "opens one by default, so the two go together.");
    }

    @Test
    @DisplayName("a migration using CONCURRENTLY also opts out of Flyway's transaction")
    void concurrentIndexesOptOutOfTheTransaction() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path migration : migrations()) {
            String sql = read(migration);
            if (!stripComments(sql).toUpperCase().contains("CONCURRENTLY")) {
                continue;
            }
            if (!sql.contains("flyway:executeInTransaction=false")) {
                offenders.add(migration.getFileName().toString());
            }
        }

        assertTrue(offenders.isEmpty(),
                "These use CONCURRENTLY inside Flyway's default transaction, which PostgreSQL rejects "
                        + "outright — the migration fails on the first installation that runs it: " + offenders
                        + ". Add `-- flyway:executeInTransaction=false` as the first line.");
    }

    @Test
    @DisplayName("the frozen list names migrations that exist")
    void exemptionListIsNotStale() throws IOException {
        Set<String> present = new TreeSet<>();
        for (Path migration : migrations()) {
            present.add(migration.getFileName().toString());
        }

        Set<String> missing = new TreeSet<>(SHIPPED_WITH_BLOCKING_INDEXES);
        missing.removeAll(present);

        assertTrue(missing.isEmpty(),
                "The frozen list names migrations that are not there: " + missing
                        + ". A stale entry hides a real one behind it.");
    }

    @Test
    @DisplayName("the scan reaches the migrations at all")
    void scanIsNotVacuous() throws IOException {
        assertFalse(migrations().isEmpty(), MIGRATIONS + " has no migrations — the scan is broken, not the code");
    }

    private static List<Path> migrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
