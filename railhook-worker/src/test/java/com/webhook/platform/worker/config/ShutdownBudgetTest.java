package com.webhook.platform.worker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over the worker's shutdown budget, which is written down in three places that no
 * language links.
 *
 * <p>How long the worker needs to stop is decided in {@code application.yml}: the lifecycle
 * phase drains the Kafka containers, and then each {@code BoundedAsyncExecutor} waits out its
 * in-flight deliveries in {@code @PreDestroy} — which runs after the lifecycle phase, does not
 * share its timeout, and runs once per pool, in turn. How long it is *given* is decided by
 * Docker ({@code stop_grace_period}) and by Kubernetes
 * ({@code terminationGracePeriodSeconds}), neither of which can see the first number.
 *
 * <p>They had already drifted: the worker was given 35s against a budget of 150, so Docker sent
 * SIGKILL while the first pool was still waiting, and the Helm chart set nothing at all and got
 * the Kubernetes default of 30s on every rollout and every HPA scale-down. Both lost in-flight
 * deliveries silently — the ladder eventually re-sent them, which is exactly why nobody noticed.
 *
 * <p>The pool count is read from {@code ExecutorConfig} rather than written here, so adding a
 * third pool fails this test instead of quietly shortening the margin.
 */
@Tag("ratchet")
class ShutdownBudgetTest {

    private static final Path APPLICATION_YML = Paths.get("src/main/resources/application.yml");
    private static final Path EXECUTOR_CONFIG =
            Paths.get("src/main/java/com/webhook/platform/worker/config/ExecutorConfig.java");
    private static final Path COMPOSE = Paths.get("..", "docker-compose.yml");
    private static final Path HELM_VALUES = Paths.get("..", "deploy", "helm", "railhook", "values.yaml");

    @Test
    @DisplayName("docker-compose.yml gives the worker at least the budget application.yml asks for")
    void composeGraceCoversTheBudget() throws IOException {
        int budget = budgetSeconds();
        int grace = seconds(composeService(read(COMPOSE), "worker"), Pattern.compile("stop_grace_period:\\s*(\\d+)s"),
                "docker-compose.yml's worker has no stop_grace_period");

        assertTrue(grace >= budget,
                "docker-compose.yml gives the worker " + grace + "s to stop, but it can need " + budget
                        + "s: " + explainBudget() + ". Docker SIGKILLs whatever is still running, and what is "
                        + "still running is an in-flight delivery.");
    }

    @Test
    @DisplayName("the Helm chart gives the worker at least the budget application.yml asks for")
    void helmGraceCoversTheBudget() throws IOException {
        int budget = budgetSeconds();
        int grace = seconds(chartComponent(read(HELM_VALUES), "worker"),
                Pattern.compile("terminationGracePeriodSeconds:\\s*(\\d+)"),
                "the chart's worker has no terminationGracePeriodSeconds — Kubernetes then applies its "
                        + "own default of 30s, which is shorter than the budget");

        assertTrue(grace >= budget,
                "deploy/helm/railhook/values.yaml gives the worker " + grace + "s to stop, but it can need "
                        + budget + "s: " + explainBudget());
    }

    @Test
    @DisplayName("the api's grace period covers its own lifecycle phase")
    void apiGraceCoversItsLifecycle() throws IOException {
        int lifecycle = lifecycleSeconds();
        int compose = seconds(composeService(read(COMPOSE), "api"), Pattern.compile("stop_grace_period:\\s*(\\d+)s"),
                "docker-compose.yml's api has no stop_grace_period");
        int helm = seconds(chartComponent(read(HELM_VALUES), "api"),
                Pattern.compile("terminationGracePeriodSeconds:\\s*(\\d+)"),
                "the chart's api has no terminationGracePeriodSeconds");

        assertTrue(compose > lifecycle, "docker-compose.yml gives the api " + compose
                + "s, which does not clear its " + lifecycle + "s lifecycle phase");
        assertTrue(helm > lifecycle, "the chart gives the api " + helm
                + "s, which does not clear its " + lifecycle + "s lifecycle phase");
    }

    // ── the budget ─────────────────────────────────────────────────────────────────

    /** Lifecycle phase, then one async-shutdown timeout per pool, in turn. */
    private int budgetSeconds() throws IOException {
        return lifecycleSeconds() + poolCount() * asyncShutdownSeconds();
    }

    private String explainBudget() throws IOException {
        return lifecycleSeconds() + "s lifecycle + " + poolCount() + " x " + asyncShutdownSeconds()
                + "s async-shutdown-timeout-seconds (one per BoundedAsyncExecutor, sequential)";
    }

    private int lifecycleSeconds() throws IOException {
        return seconds(read(APPLICATION_YML), Pattern.compile("timeout-per-shutdown-phase:\\s*(\\d+)s"),
                "application.yml declares no spring.lifecycle.timeout-per-shutdown-phase");
    }

    private int asyncShutdownSeconds() throws IOException {
        return seconds(read(APPLICATION_YML),
                Pattern.compile("async-shutdown-timeout-seconds:\\s*\\$\\{[A-Z_]+:(\\d+)}"),
                "application.yml declares no webhook.async-shutdown-timeout-seconds default");
    }

    private int poolCount() throws IOException {
        String config = read(EXECUTOR_CONFIG);
        int count = 0;
        Matcher m = Pattern.compile("new BoundedAsyncExecutor\\(").matcher(config);
        while (m.find()) {
            count++;
        }
        assertTrue(count > 0, "ExecutorConfig builds no BoundedAsyncExecutor — has the shutdown path moved?");
        return count;
    }

    // ── reading ────────────────────────────────────────────────────────────────────

    /**
     * The block belonging to one service or component key — everything from that key to the
     * next one at the same indent. A whole-file regex would match the api's value while
     * asserting about the worker. Compose nests its services two spaces in; the chart's values
     * are top-level, hence the indent argument.
     */
    private static String section(String document, String name, int indent) {
        String pad = " ".repeat(indent);
        Matcher start = Pattern.compile("(?m)^" + pad + Pattern.quote(name) + ":\\s*$").matcher(document);
        assertTrue(start.find(), "no '" + pad + name + ":' section found — did the file's shape change?");
        Matcher next = Pattern.compile("(?m)^" + pad + "[A-Za-z]").matcher(document);
        int end = document.length();
        if (next.find(start.end())) {
            end = next.start();
        }
        return document.substring(start.end(), end);
    }

    /** A Compose service. */
    private static String composeService(String document, String name) {
        return section(document, name, 2);
    }

    /** A top-level key in the chart's values. */
    private static String chartComponent(String document, String name) {
        return section(document, name, 0);
    }

    private static int seconds(String text, Pattern pattern, String absenceMessage) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), absenceMessage);
        return Integer.parseInt(m.group(1));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the budget is read, not guessed")
    void budgetIsDerivedFromConfiguration() throws IOException {
        assertEquals(2, poolCount(), "ExecutorConfig no longer builds exactly two pools — the grace periods "
                + "in docker-compose.yml and values.yaml were sized for two and need revisiting");
    }
}
