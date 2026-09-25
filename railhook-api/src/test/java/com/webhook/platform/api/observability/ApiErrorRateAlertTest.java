package com.webhook.platform.api.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// Tunnel 5xx relay the customer's own server, and the ratio used to print as a percent.
class ApiErrorRateAlertTest {

    private static final List<Path> RULE_FILES = List.of(
            Paths.get("..", "monitoring", "prometheus", "alerts.yml"),
            Paths.get("..", "deploy", "helm", "railhook", "templates", "prometheusrule.yaml"));

    @Test
    @DisplayName("a customer's tunnel answering 5xx is not the API failing")
    void tunnelTrafficIsLeftOut() throws IOException {
        for (Path file : RULE_FILES) {
            String expr = exprOf(Files.readString(file), "ApiErrorRateHigh");
            int series = countOf(expr, "http_server_requests_seconds_count");
            assertThat(series).as(file + ": numerator, denominator and error floor").isEqualTo(3);
            assertThat(countOf(expr, "uri!~\"/tunnel/.*\""))
                    .as(file + ": every series in the rule must leave tunnel traffic out").isEqualTo(series);
        }
    }

    // A ratio alone cannot tell a quiet deployment's two failures from an outage.
    @Test
    @DisplayName("two or three errors on a quiet deployment do not page")
    void aFewErrorsDoNotPage() throws IOException {
        Pattern floor = Pattern.compile(
                "and\\s+sum\\(increase\\(http_server_requests_seconds_count\\{status=~\"5\\.\\.\"[^}]*}\\[5m]\\)\\)\\s*>=\\s*(\\d+)");
        for (Path file : RULE_FILES) {
            Matcher m = floor.matcher(exprOf(Files.readString(file), "ApiErrorRateHigh"));
            assertThat(m.find()).as(file + ": the rule needs a minimum count of 5xx in the window").isTrue();
            assertThat(Integer.parseInt(m.group(1))).as(file + ": the floor").isGreaterThanOrEqualTo(5);
        }
    }

    @Test
    @DisplayName("a ratio is not printed as a percentage")
    void ratiosAreHumanized() throws IOException {
        String compose = Files.readString(RULE_FILES.get(0));
        assertThat(compose)
                .as("$value of a ratio printed with a trailing % reads 100 times too small")
                .doesNotContainPattern(Pattern.compile("\\$value \\| printf \"%\\.\\d+f\" }}%"));
    }

    private static String exprOf(String rules, String alert) {
        Matcher m = Pattern.compile("-\\s*alert:\\s*" + alert + "\\s*\\n\\s*expr:\\s*\\|\\s*\\n((?:\\s+[^\\n]*\\n)+?)\\s*for:")
                .matcher(rules);
        assertThat(m.find()).as(alert + " not found").isTrue();
        return m.group(1);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
