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
    void listsEvents_mostRecentLast() throws Exception {
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
        assertTrue(output.contains("deliveries: 2"));
        assertTrue(output.contains("deliveries: 1"));
        // The page comes newest-first and is printed reversed, like a tail.
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
}
