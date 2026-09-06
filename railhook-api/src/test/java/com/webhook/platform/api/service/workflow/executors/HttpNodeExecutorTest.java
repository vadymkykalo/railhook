package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses a local JDK HttpServer (no extra test deps) bound to loopback, so
 * {@code allowPrivateIps=true} is required for the "success" tests — SSRF
 * blocking itself is tested separately with the default (false) setting.
 */
class HttpNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpNodeExecutor executor(boolean allowPrivateIps) {
        return new HttpNodeExecutor(WebClient.builder(), mapper, allowPrivateIps, List.of());
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void getType_returnsHttp() {
        assertThat(executor(true).getType()).isEqualTo("http");
    }

    @Test
    void missingUrl_returnsFailed() throws Exception {
        StepResult result = executor(true).execute(json("{}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("url is required");
    }

    @Test
    void privateIpBlockedByDefault_returnsFailed() throws Exception {
        JsonNode config = json("{\"url\":\"" + baseUrl + "/\"}");

        StepResult result = executor(false).execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("SSRF blocked");
    }

    @Test
    void successfulPost_returnsStatusAndParsedJsonBody() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        server.createContext("/hook", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        JsonNode config = json("{\"url\":\"" + baseUrl + "/hook\"}");
        JsonNode input = json("{\"orderId\":\"o-1\"}");

        StepResult result = executor(true).execute(config, input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("statusCode").asInt()).isEqualTo(200);
        assertThat(result.output().get("body").get("ok").asBoolean()).isTrue();
        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedBody.get()).isEqualTo(input.toString());
    }

    @Test
    void explicitMethodAndBody_areUsedInsteadOfDefaults() throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext("/hook", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        JsonNode config = json("{\"url\":\"" + baseUrl + "/hook\",\"method\":\"PUT\",\"body\":\"custom-body\"}");

        StepResult result = executor(true).execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(receivedMethod.get()).isEqualTo("PUT");
        assertThat(receivedBody.get()).isEqualTo("custom-body");
    }

    @Test
    void customHeaders_areForwarded_exceptDangerousOnes() throws Exception {
        AtomicReference<String> customHeader = new AtomicReference<>();
        server.createContext("/hook", exchange -> {
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Custom"));
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        JsonNode config = json("{\"url\":\"" + baseUrl + "/hook\",\"headers\":{\"X-Custom\":\"value1\",\"Host\":\"evil.example\"}}");

        StepResult result = executor(true).execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(customHeader.get()).isEqualTo("value1");
    }

    @Test
    void non2xxResponse_returnsFailed() throws Exception {
        server.createContext("/hook", exchange -> {
            byte[] resp = "server error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        JsonNode config = json("{\"url\":\"" + baseUrl + "/hook\"}");

        StepResult result = executor(true).execute(config, json("{}"));

        // WebClient's default retrieve() raises WebClientResponseException for any
        // non-2xx status before the executor's own "statusCode >= 200 && < 300" check
        // ever runs, so the failure is surfaced via the generic catch block, not the
        // "HTTP <code>: <body>" branch further down in HttpNodeExecutor#execute.
        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("HTTP error").contains("500");
    }

    @Test
    void unreachableHost_failsWithinConfiguredTimeout() throws Exception {
        // Close the server immediately so nothing is listening on this port, and bound
        // the executor's own wait via a short "timeout" config value rather than relying
        // on how quickly the OS/sandbox reports connection refusal.
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;

        JsonNode config = json("{\"url\":\"http://127.0.0.1:" + port + "/nope\",\"timeout\":1}");

        long start = System.currentTimeMillis();
        StepResult result = executor(true).execute(config, json("{}"));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("HTTP error");
        assertThat(elapsed).isLessThan(10_000);
    }
}
