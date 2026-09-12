package com.webhook.platform.api.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over the two places an alert rule is written down.
 *
 * <p>A Compose deployment reads {@code monitoring/prometheus/alerts.yml}; a Kubernetes one
 * reads the PrometheusRule this chart renders. The two describe the same platform and nothing
 * connects them, so they drifted — and a third file,
 * {@code deploy/prometheus/alerts.yml}, sat between them being mounted by nobody while looking
 * authoritative enough that a missing rule appeared to exist.
 *
 * <p>What that cost: the Helm set was four rules short, and
 * {@code outbox_oldest_pending_age_seconds} — the third of the three signals
 * {@code docs/guides/observability.md} tells an operator to alert on if they alert on nothing
 * else — had no rule in any of the three. This test makes the two surviving sets one set.
 */
@Tag("ratchet")
class AlertRuleParityTest {

    private static final Pattern ALERT_NAME = Pattern.compile("(?m)^\\s*-\\s*alert:\\s*(\\S+)\\s*$");

    private static final Path COMPOSE_RULES =
            Paths.get("..", "monitoring", "prometheus", "alerts.yml");
    private static final Path CHART_RULES =
            Paths.get("..", "deploy", "helm", "railhook", "templates", "prometheusrule.yaml");
    private static final Path REMOVED_THIRD_COPY =
            Paths.get("..", "deploy", "prometheus", "alerts.yml");

    @Test
    @DisplayName("the Compose rules and the chart's rules are the same set")
    void bothDeploymentsAlertOnTheSameThings() throws IOException {
        Set<String> compose = alertNames(COMPOSE_RULES);
        Set<String> chart = alertNames(CHART_RULES);

        assertFalse(compose.isEmpty(), COMPOSE_RULES + " declares no alerts — has the format changed?");

        Set<String> onlyInCompose = new TreeSet<>(compose);
        onlyInCompose.removeAll(chart);
        Set<String> onlyInChart = new TreeSet<>(chart);
        onlyInChart.removeAll(compose);

        assertTrue(onlyInCompose.isEmpty() && onlyInChart.isEmpty(),
                "The two alert sets have drifted. A Kubernetes operator and a Compose operator "
                        + "must not be watching different conditions on the same platform.\n"
                        + "  Only in monitoring/prometheus/alerts.yml: " + onlyInCompose + "\n"
                        + "  Only in the chart's prometheusrule.yaml:  " + onlyInChart + "\n"
                        + "Add the rule to both, or remove it from both.");
    }

    @Test
    @DisplayName("the three signals the observability guide names all have a rule")
    void theThreeNamedSignalsAreCovered() throws IOException {
        String compose = read(COMPOSE_RULES);
        String chart = read(CHART_RULES);

        // docs/guides/observability.md, "If you only alert on three things".
        for (String metric : new String[]{
                "delivery_oldest_pending_age_seconds",
                "circuit_breaker_degraded_total",
                "outbox_oldest_pending_age_seconds"}) {
            assertTrue(compose.contains(metric),
                    metric + " is named in docs/guides/observability.md as one of the three signals to "
                            + "alert on, but no Compose rule uses it.");
            assertTrue(chart.contains(metric),
                    metric + " is named in docs/guides/observability.md as one of the three signals to "
                            + "alert on, but no chart rule uses it.");
        }
    }

    @Test
    @DisplayName("both sets say whether the services are running at all")
    void bothSetsCoverLiveness() throws IOException {
        for (Path rules : new Path[]{COMPOSE_RULES, CHART_RULES}) {
            Set<String> names = alertNames(rules);
            assertTrue(names.contains("ApiDown") && names.contains("WorkerDown"),
                    rules + " has no up == 0 rule. Without one a process that dies outright trips "
                            + "nothing directly, and shows up minutes later as a backlog someone has to "
                            + "interpret.");
        }
    }

    @Test
    @DisplayName("the unmounted third copy of the rules has not come back")
    void theThirdCopyStaysGone() {
        assertFalse(Files.exists(REMOVED_THIRD_COPY),
                REMOVED_THIRD_COPY + " is back. Nothing mounts it — not docker-compose.yml, not "
                        + "monitoring/docker-compose.yml, not the chart — so rules added there protect no "
                        + "deployment while looking as though they do. That is how OutboxSendingStuck came "
                        + "to be believed present for so long.");
    }

    private static Set<String> alertNames(Path path) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = ALERT_NAME.matcher(read(path));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("neither set has picked up a duplicate rule name")
    void noDuplicateAlertNames() throws IOException {
        for (Path rules : new Path[]{COMPOSE_RULES, CHART_RULES}) {
            Matcher m = ALERT_NAME.matcher(read(rules));
            int total = 0;
            Set<String> unique = new LinkedHashSet<>();
            while (m.find()) {
                total++;
                unique.add(m.group(1));
            }
            assertEquals(total, unique.size(), rules + " declares the same alert name twice");
        }
    }
}
