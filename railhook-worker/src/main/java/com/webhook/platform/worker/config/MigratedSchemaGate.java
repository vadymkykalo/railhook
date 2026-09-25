package com.webhook.platform.worker.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Holds the worker until the API has migrated the schema: validating against a half-migrated
 * schema exited on a missing column. The required version is the highest API migration bundled
 * at build time; a newer schema, after a rollback, starts at once.
 */
@Slf4j
public class MigratedSchemaGate {

    static final String BUNDLED_MIGRATIONS = "classpath*:db/expected-migrations/V*__*.sql";

    // Long enough for the slowest migration shipped so far on a large installation.
    static final Duration TIMEOUT = Duration.ofMinutes(15);

    static final Duration POLL = Duration.ofSeconds(5);

    private static final Duration LOG_EVERY = Duration.ofSeconds(30);

    @FunctionalInterface
    interface AppliedVersion {
        Optional<String> highest() throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final String required;
    private final AppliedVersion applied;
    private final Supplier<Instant> clock;
    private final Sleeper sleeper;

    MigratedSchemaGate(String required, AppliedVersion applied, Supplier<Instant> clock, Sleeper sleeper) {
        this.required = required;
        this.applied = applied;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    void await() {
        Instant started = clock.get();
        Instant deadline = started.plus(TIMEOUT);
        Instant nextLog = started;
        boolean waited = false;

        while (true) {
            Optional<String> highest;
            String unreachable = null;
            try {
                highest = applied.highest();
            } catch (Exception e) {
                highest = Optional.empty();
                unreachable = e.getMessage();
            }

            if (highest.isPresent() && compareVersions(highest.get(), required) >= 0) {
                if (waited) {
                    log.info("Schema is at version {}; this worker needs {} — starting", highest.get(), required);
                }
                return;
            }

            Instant now = clock.get();
            String current = highest.orElse("none");
            if (!now.isBefore(deadline)) {
                throw new IllegalStateException("The database schema is at version " + current
                        + " and this worker needs " + required + ", and it did not get there within "
                        + TIMEOUT.toMinutes() + " minutes. Only the API runs migrations: start the API of "
                        + "the same release, let it finish, then start the worker."
                        + (unreachable == null ? "" : " Last database error: " + unreachable));
            }
            if (!now.isBefore(nextLog)) {
                log.info("Waiting for the API to migrate the schema to version {} before validating it "
                        + "(now at {}{})", required, current,
                        unreachable == null ? "" : "; database not reachable: " + unreachable);
                nextLog = now.plus(LOG_EVERY);
            }
            waited = true;
            try {
                sleeper.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the schema to reach " + required, e);
            }
        }
    }

    static String requiredVersion(ResourcePatternResolver resolver) throws IOException {
        String highest = null;
        for (Resource resource : resolver.getResources(BUNDLED_MIGRATIONS)) {
            String name = resource.getFilename();
            if (name == null || !name.startsWith("V") || !name.contains("__")) {
                continue;
            }
            String version = name.substring(1, name.indexOf("__"));
            if (highest == null || compareVersions(version, highest) > 0) {
                highest = version;
            }
        }
        if (highest == null) {
            throw new IllegalStateException("No migrations bundled at " + BUNDLED_MIGRATIONS
                    + ": the worker build copies them from railhook-api, and this one did not.");
        }
        return highest;
    }

    static AppliedVersion fromDatabase(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL")) {
                String highest = null;
                while (rows.next()) {
                    String version = rows.getString(1);
                    if (highest == null || compareVersions(version, highest) > 0) {
                        highest = version;
                    }
                }
                return Optional.ofNullable(highest);
            } catch (SQLException e) {
            // undefined_table: Flyway has never run against this database.
                if ("42P01".equals(e.getSQLState())) {
                    return Optional.empty();
                }
                throw e;
            }
        };
    }

    // Flyway's ordering: dot- or underscore-separated numbers, compared part by part.
    static int compareVersions(String a, String b) {
        String[] left = a.split("[._]");
        String[] right = b.split("[._]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            BigInteger l = i < left.length ? new BigInteger(left[i]) : BigInteger.ZERO;
            BigInteger r = i < right.length ? new BigInteger(right[i]) : BigInteger.ZERO;
            int cmp = l.compareTo(r);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
