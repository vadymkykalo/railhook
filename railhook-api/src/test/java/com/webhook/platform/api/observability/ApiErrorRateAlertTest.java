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

/**
 * ApiErrorRateHigh is the critical page for the API, and it paged on 2026-09-18 for three requests.
 *
 * <p>A tunnel relays the answer of a server on the customer's own machine, and answers 503 while
 * no client is connected — a customer's dev server returning 500, or their laptop being shut,
 * counted as Railhook failing. On a quiet deployment three such requests were 2% of traffic.
 *
 * <p>The page then said "0.02% of requests returning 5xx" under a 0.5% threshold: the expression
 * is a ratio and the template printed it with a percent sign.
 */
class ApiErrorRateAlertTest {

    private static final List<Path> RULE_FILES = List.of(
            Paths.get("..", "monitoring", "prometheus", "alerts.yml"),
            Paths.get("..", "deploy", "helm", "railhook", "templates", "prometheusrule.yaml"));

    @Test
    @DisplayName("a customer's tunnel answering 5xx is not the API failing")
    void tunnelTrafficIsLeftOut() throws IOException {
        for (Path file : RULE_FILES) {
            String expr = exprOf(Files.readString(file), "ApiErrorRateHigh");
            assertThat(countOf(expr, "http_server_requests_seconds_count"))
                    .as(file + ": numerator and denominator").isEqualTo(2);
            assertThat(countOf(expr, "uri!~\"/tunnel/.*\""))
                    .as(file + ": both sides of the ratio must leave tunnel traffic out").isEqualTo(2);
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
