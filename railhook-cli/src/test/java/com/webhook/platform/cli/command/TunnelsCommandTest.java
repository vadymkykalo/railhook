package com.webhook.platform.cli.command;

import com.webhook.platform.cli.config.CliConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TunnelsCommandTest extends CliCommandTestBase {

    private void respond(String path, int status, String json) {
        server.createContext(path, exchange -> {
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "close", "status"})
    void notAuthenticated_exitsOne(String subcommand) throws Exception {
        writeConfig(new CliConfig());

        int exitCode = "close".equals(subcommand)
                ? run("tunnels", subcommand, "tun-1")
                : run("tunnels", subcommand);

        assertEquals(1, exitCode);
        assertTrue(err().contains("Not authenticated"));
    }

    @Test
    void bareTunnels_behavesLikeList() throws Exception {
        respond("/api/v1/tunnels", 200, "[]");
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("tunnels");

        assertEquals(0, exitCode);
        assertTrue(out().contains("No active tunnels."));
    }

    @Test
    void list_withTunnels_printsTable() throws Exception {
        respond("/api/v1/tunnels", 200,
                "[{\"id\":\"tun-1\",\"localPort\":3000,\"publicUrl\":\"https://tun.example/abc\",\"status\":\"ACTIVE\"}]");
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("tunnels", "list");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("tun-1"));
        assertTrue(output.contains("3000"));
        assertTrue(output.contains("tun.example/abc"));
        assertTrue(output.contains("Total: 1"));
    }

    @Test
    void close_sendsDeleteAndPrintsConfirmation() throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        server.createContext("/api/v1/tunnels/tun-42", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
        });
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("tunnels", "close", "tun-42");

        assertEquals(0, exitCode);
        assertEquals("DELETE", receivedMethod.get());
        assertTrue(out().contains("Tunnel tun-42 closed"));
    }

    @Test
    void status_printsBandwidthAndMyTunnels() throws Exception {
        respond("/api/v1/tunnels/status", 200,
                "{\"activeTunnels\":3,\"pendingRequests\":1,\"bandwidthBytesThisMonth\":2097152," +
                        "\"myTunnels\":[{\"publicUrl\":\"https://tun.example/xyz\",\"localPort\":4000,\"status\":\"ACTIVE\"}]}");
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("tunnels", "status");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("Active tunnels:    3"));
        // The byte count is formatted with the default locale, so the separator may be a comma.
        assertTrue(output.matches("(?s).*2[.,]0 MB.*"), "expected a 2.0 MB bandwidth line, got:\n" + output);
        assertTrue(output.contains("tun.example/xyz"));
    }
}
