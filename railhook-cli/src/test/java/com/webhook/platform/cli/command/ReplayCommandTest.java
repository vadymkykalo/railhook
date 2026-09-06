package com.webhook.platform.cli.command;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReplayCommandTest extends CliCommandTestBase {

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws java.io.IOException {
        byte[] resp = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    @Test
    void notAuthenticated_exitsOneWithoutCallingBackend() throws Exception {
        writeConfig(new com.webhook.platform.cli.config.CliConfig());

        int exitCode = run("replay", "proj-1");

        assertEquals(1, exitCode);
        assertTrue(err().contains("Not authenticated"));
    }

    @Test
    void dryRun_printsEstimateFromBackend() throws Exception {
        server.createContext("/api/v1/projects/proj-1/replay/estimate", exchange ->
                respondJson(exchange, 200, "{\"matchingEvents\":42,\"estimatedDeliveries\":84}"));
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("replay", "proj-1", "--dry-run");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("Replay Estimate"));
        assertTrue(output.contains("Events matched:     42"));
        assertTrue(output.contains("Deliveries created: 84"));
    }

    @Test
    void dryRun_withEventTypeFilter_includesItInOutput() throws Exception {
        server.createContext("/api/v1/projects/proj-1/replay/estimate", exchange ->
                respondJson(exchange, 200, "{\"matchingEvents\":1,\"estimatedDeliveries\":1}"));
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("replay", "proj-1", "--dry-run", "--event-type", "order.created");

        assertEquals(0, exitCode);
        assertTrue(out().contains("Event type:         order.created"));
    }

    @Test
    void fullReplay_pollsUntilCompleted_andPrintsFinalStatus() throws Exception {
        server.createContext("/api/v1/projects/proj-1/replay", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            respondJson(exchange, 200, "{\"id\":\"session-1\",\"status\":\"RUNNING\"}");
        });
        AtomicInteger pollCount = new AtomicInteger();
        server.createContext("/api/v1/projects/proj-1/replay/session-1", exchange -> {
            pollCount.incrementAndGet();
            respondJson(exchange, 200,
                    "{\"status\":\"COMPLETED\",\"processedEvents\":10,\"deliveriesCreated\":10}");
        });
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("replay", "proj-1");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("Replay session created"));
        assertTrue(output.contains("Session ID:  session-1"));
        assertTrue(output.contains("Final status:      COMPLETED"));
        assertTrue(output.contains("Events processed:  10"));
        assertEquals(1, pollCount.get(), "should stop polling as soon as a terminal status is seen");
    }

    @Test
    void fullReplay_failedSession_returnsNonZeroExitCode() throws Exception {
        server.createContext("/api/v1/projects/proj-1/replay", exchange ->
                respondJson(exchange, 200, "{\"id\":\"session-2\",\"status\":\"RUNNING\"}"));
        server.createContext("/api/v1/projects/proj-1/replay/session-2", exchange ->
                respondJson(exchange, 200, "{\"status\":\"FAILED\",\"processedEvents\":3,\"deliveriesCreated\":0}"));
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("replay", "proj-1");

        assertEquals(1, exitCode);
        assertTrue(out().contains("Final status:      FAILED"));
    }
}
