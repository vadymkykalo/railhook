package com.webhook.platform.worker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The worker does not validate its entities against a schema the API has not finished migrating.
 *
 * <p>Only the API runs Flyway; the worker starts with {@code ddl-auto: validate}. Started beside
 * or before an API that is still migrating — a fresh install, {@code railhook start} after a tag
 * change, a Helm upgrade that rolls both Deployments at once — it validated the old schema and
 * exited ("Schema validation: missing column [ladder_resumed_at]", the 2.20.7 deploy), and only a
 * restart brought it back.
 */
class MigratedSchemaGateTest {

    private static final Path API_MIGRATIONS = Paths.get("..", "railhook-api", "src", "main", "resources", "db", "migration");
    private static final Path HELM_VALUES = Paths.get("..", "deploy", "helm", "railhook", "values.yaml");

    @Test
    @DisplayName("the worker waits for the highest migration the API of its release ships")
    void requiredVersionIsTheApisHighestMigration() throws IOException {
        String highest;
        try (Stream<Path> files = Files.list(API_MIGRATIONS)) {
            highest = files.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("V[0-9._]+__.+\\.sql"))
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .max(MigratedSchemaGate::compareVersions)
                    .orElseThrow();
        }

        assertThat(MigratedSchemaGate.requiredVersion(new PathMatchingResourcePatternResolver()))
                .isEqualTo(highest);
    }

    @Test
    void versionsCompareAsNumbers() {
        assertThat(MigratedSchemaGate.compareVersions("076", "76")).isZero();
        assertThat(MigratedSchemaGate.compareVersions("100", "099")).isPositive();
        assertThat(MigratedSchemaGate.compareVersions("1.10", "1.9")).isPositive();
        assertThat(MigratedSchemaGate.compareVersions("1_2", "1.2")).isZero();
        assertThat(MigratedSchemaGate.compareVersions("1", "1.0.1")).isNegative();
    }

    @Test
    void waitsUntilTheApiHasMigrated_thenLetsTheWorkerStart() {
        Deque<Optional<String>> applied = new ArrayDeque<>(List.of(
                Optional.empty(), Optional.of("075"), Optional.of("076")));
        FakeTime time = new FakeTime();

        gate("076", () -> applied.size() > 1 ? applied.poll() : applied.peek(), time).await();

        assertThat(time.sleeps.get()).isEqualTo(2);
    }

    @Test
    void startsAtOnceOnASchemaAheadOfIt_asAfterAnImageRollback() {
        FakeTime time = new FakeTime();

        gate("076", () -> Optional.of("080"), time).await();

        assertThat(time.sleeps.get()).isZero();
    }

    @Test
    void anUnreachableDatabaseIsWaitedFor_notFatal() {
        AtomicInteger calls = new AtomicInteger();
        FakeTime time = new FakeTime();

        gate("076", () -> {
            if (calls.getAndIncrement() == 0) {
                throw new java.sql.SQLException("Connection refused");
            }
            return Optional.of("076");
        }, time).await();

        assertThat(time.sleeps.get()).isEqualTo(1);
    }

    @Test
    void givesUpAfterItsTimeout_namingBothVersions() {
        FakeTime time = new FakeTime();

        assertThatThrownBy(() -> gate("076", () -> Optional.of("074"), time).await())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("074")
                .hasMessageContaining("076");
        assertThat(time.now.get()).isAfterOrEqualTo(Instant.EPOCH.plus(MigratedSchemaGate.TIMEOUT));
    }

    @Test
    @DisplayName("the chart lets a waiting worker live as long as the gate waits")
    void chartStartupProbeOutlastsTheGate() throws IOException {
        String values = Files.readString(HELM_VALUES, StandardCharsets.UTF_8);
        int worker = values.indexOf("\nworker:");
        assertThat(worker).as("values.yaml has no top-level worker block").isNotNegative();
        String block = values.substring(worker + 1);
        Matcher nextTopLevel = Pattern.compile("\\n[a-zA-Z]").matcher(block);
        if (nextTopLevel.find()) {
            block = block.substring(0, nextTopLevel.start());
        }
        int startup = block.indexOf("startupProbe:");
        assertThat(startup)
                .as("the chart's worker has no startupProbe, so its livenessProbe kills a worker still "
                        + "waiting for the API to migrate after about 90 seconds")
                .isNotNegative();
        String probe = block.substring(startup, Math.min(block.length(), startup + 400));
        long seconds = number(probe, "periodSeconds") * number(probe, "failureThreshold");

        assertThat(Duration.ofSeconds(seconds)).isGreaterThanOrEqualTo(MigratedSchemaGate.TIMEOUT);
    }

    private static long number(String yaml, String key) {
        Matcher m = Pattern.compile(key + ":\\s*(\\d+)").matcher(yaml);
        assertThat(m.find()).as("startupProbe has no " + key).isTrue();
        return Long.parseLong(m.group(1));
    }

    private static MigratedSchemaGate gate(String required, MigratedSchemaGate.AppliedVersion applied, FakeTime time) {
        return new MigratedSchemaGate(required, applied, time.now::get, time::sleep);
    }

    private static final class FakeTime {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        final AtomicInteger sleeps = new AtomicInteger();

        void sleep(Duration duration) {
            sleeps.incrementAndGet();
            now.updateAndGet(t -> t.plus(duration));
        }
    }
}
