package com.webhook.platform.cli.command;

import com.webhook.platform.cli.config.CliConfig;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class StatusCommandTest extends CliCommandTestBase {

    @Test
    void notAuthenticated_printsLoginHint_andExitsZero() throws Exception {
        CliConfig config = new CliConfig();
        config.setBackendUrl(backendUrl);
        writeConfig(config);

        int exitCode = run("status");

        assertEquals(0, exitCode);
        assertTrue(out().contains("not authenticated"));
        assertTrue(out().contains("railhook login"));
        // Must return before ever attempting to reach the backend.
        assertFalse(out().contains("Health:"));
    }

    @Test
    void authenticated_healthyBackend_printsHealthAndTunnels() throws Exception {
        server.createContext("/actuator/health", exchange -> {
            byte[] resp = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/api/v1/tunnels/status", exchange -> {
            byte[] resp = "{\"activeTunnels\":2,\"pendingRequests\":0,\"myTunnels\":[{\"publicUrl\":\"https://tun.example/abc\",\"localPort\":3000,\"status\":\"ACTIVE\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        writeConfig(authenticatedConfig());

        int exitCode = run("status");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("authenticated"));
        assertTrue(output.contains("user-1"));
        assertTrue(output.contains("org-1"));
        assertTrue(output.contains("UP"));
        assertTrue(output.contains("Active:   2"));
        assertTrue(output.contains("tun.example/abc"));
    }

    @Test
    void authenticated_backendUnreachable_printsUnreachableInsteadOfCrashing() throws Exception {
        // HttpServer.create() already binds the listening socket even before start(),
        // so stop() it now to get a genuine "connection refused" instead of a TCP
        // connection that's accepted but never serviced (which would just hang until
        // getHealth()'s 5s connect timeout).
        server.stop(0);
        writeConfig(authenticatedConfig());

        int exitCode = run("status");

        assertEquals(0, exitCode);
        assertTrue(out().contains("unreachable"));
    }
}
