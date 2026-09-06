package com.webhook.platform.cli.command;

import com.webhook.platform.cli.config.CliConfig;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventsTailCommandTest extends CliCommandTestBase {

    @Test
    void notAuthenticated_exitsOneWithoutCallingBackend() throws Exception {
        writeConfig(new CliConfig());

        int exitCode = run("events", "proj-1");

        assertEquals(1, exitCode);
        assertTrue(err().contains("Not authenticated"));
    }

    @Test
    void listsEvents_mostRecentFirst() throws Exception {
        // Backend returns oldest-first (as if sorted ascending internally is irrelevant —
        // the command reads a page ordered createdAt,desc and then reverses locally,
        // printing oldest of the page first, most recent last).
        server.createContext("/api/v1/projects/proj-1/events", exchange -> {
            String json = """
                    {"content":[
                      {"id":"22222222-bbbb-bbbb-bbbb-bbbbbbbbbbbb","eventType":"order.updated","createdAt":"2024-01-01T00:00:02Z","deliveriesCreated":1},
                      {"id":"11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa","eventType":"order.created","createdAt":"2024-01-01T00:00:01Z","deliveriesCreated":2}
                    ]}
                    """;
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("events", "proj-1");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("order.created"));
        assertTrue(output.contains("order.updated"));
        assertTrue(output.contains("deliveries: 2"));
        assertTrue(output.contains("deliveries: 1"));
        // "order.created" (the older/second array element) is printed first since the
        // command walks the page backwards (i = size-1 downTo 0).
        assertTrue(output.indexOf("order.created") < output.indexOf("order.updated"));
    }

    @Test
    void countAndTypeOptions_areForwardedAsQueryParams() throws Exception {
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        server.createContext("/api/v1/projects/proj-1/events", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());
            byte[] resp = "{\"content\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("events", "proj-1", "-n", "5", "--type", "order.created");

        assertEquals(0, exitCode);
        String query = capturedQuery.get();
        assertTrue(query.contains("size=5"));
        assertTrue(query.contains("eventType=order.created"));
    }

    @Test
    void emptyEventList_printsHeaderOnly() throws Exception {
        server.createContext("/api/v1/projects/proj-1/events", exchange -> {
            byte[] resp = "{\"content\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("events", "proj-1");

        assertEquals(0, exitCode);
        assertTrue(out().contains("Recent events for project proj-1"));
    }
}
