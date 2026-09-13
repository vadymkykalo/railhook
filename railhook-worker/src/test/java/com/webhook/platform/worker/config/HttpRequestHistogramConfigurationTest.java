package com.webhook.platform.worker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over the latency buckets both services publish for their HTTP requests.
 *
 * <p>Spring exports {@code http_server_requests_seconds} as a count and a sum only. Without
 * buckets {@code histogram_quantile} has nothing to read, and every latency percentile panel —
 * the overview's p95 and the JVM dashboard's p50 to p99 — showed "No data". Service-level
 * boundaries rather than {@code percentiles-histogram}: a fixed handful of buckets per URI and
 * status instead of seventy.
 */
@Tag("ratchet")
class HttpRequestHistogramConfigurationTest {

    private static final Pattern SLO_BUCKETS = Pattern.compile(
            "slo:\\s*\\n\\s*\"\\[http\\.server\\.requests]\":\\s*\\d+ms(,\\s*[\\d.]+m?s)+");

    @Test
    @DisplayName("the worker publishes latency buckets for http.server.requests")
    void workerPublishesLatencyBuckets() throws IOException {
        assertPublishesBuckets(Paths.get("src/main/resources/application.yml"), "the worker");
    }

    @Test
    @DisplayName("the api publishes latency buckets for http.server.requests")
    void apiPublishesLatencyBuckets() throws IOException {
        assertPublishesBuckets(Paths.get("../railhook-api/src/main/resources/application.yml"), "the api");
    }

    private static void assertPublishesBuckets(Path applicationYml, String service) throws IOException {
        String yml = Files.readString(applicationYml, StandardCharsets.UTF_8);
        assertTrue(SLO_BUCKETS.matcher(yml).find(),
                service + " must set management.metrics.distribution.slo.\"[http.server.requests]\" in "
                        + applicationYml + ", or latency percentile panels have no buckets to read");
    }
}
