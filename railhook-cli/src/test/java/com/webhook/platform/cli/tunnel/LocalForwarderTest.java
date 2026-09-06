package com.webhook.platform.cli.tunnel;

import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalForwarderTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startLocalServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopLocalServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnConnectionRefusedWhenPortNotListening() {
        // Use a port that is almost certainly not in use
        LocalForwarder forwarder = new LocalForwarder(19999);

        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("test-req-001")
                .method("GET")
                .path("/health")
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        assertNotNull(response);
        assertEquals("test-req-001", response.getRequestId());
        assertEquals(502, response.getStatusCode());
        assertNotNull(response.getError());
        assertTrue(response.getError().contains("Connection refused") || response.getError().contains("not reachable")
                || response.getError().contains("error"), "Expected connection error, got: " + response.getError());
        assertTrue(response.getDurationMs() >= 0);
    }

    @Test
    void shouldHandleNullPath() {
        LocalForwarder forwarder = new LocalForwarder(19999);

        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("test-req-002")
                .method("GET")
                .path(null)
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        assertNotNull(response);
        assertEquals("test-req-002", response.getRequestId());
        assertEquals(502, response.getStatusCode());
    }

    @Test
    void shouldPreserveRequestIdInResponse() {
        LocalForwarder forwarder = new LocalForwarder(19999);

        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("correlation-id-xyz")
                .method("POST")
                .path("/webhook")
                .body("{\"test\":true}")
                .headers(Map.of("Content-Type", "application/json"))
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        assertNotNull(response);
        assertEquals("correlation-id-xyz", response.getRequestId());
    }

    @Test
    void shouldHandleQueryString() {
        LocalForwarder forwarder = new LocalForwarder(19999);

        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("test-req-003")
                .method("GET")
                .path("/callback")
                .queryString("code=abc&state=xyz")
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        assertNotNull(response);
        assertEquals("test-req-003", response.getRequestId());
        // Even though connection is refused, the request was properly constructed
        assertEquals(502, response.getStatusCode());
    }

    // ─── Round-trip: request actually reaches a listening local server ────
    // This is the CLI-side half of a tunnel round trip — the server-side half
    // (WS session registration, TUNNEL_REQUEST dispatch, response correlation)
    // is already covered end-to-end by TunnelFlowIntegrationTest in
    // railhook-api. LocalForwarder is the piece unique to the CLI: it
    // takes a decoded TunnelRequestMessage and must faithfully replay it against
    // localhost:<port>, then faithfully capture whatever comes back.

    @Test
    void forward_getRequest_reachesServerAndReturnsRealResponse() throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        server.createContext("/hello", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedPath.set(exchange.getRequestURI().toString());
            byte[] resp = "{\"greeting\":\"hi\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-App-Header", "app-value");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        LocalForwarder forwarder = new LocalForwarder(port);
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("round-trip-1")
                .method("GET")
                .path("/hello")
                .queryString("name=world")
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        assertEquals("GET", receivedMethod.get());
        assertEquals("/hello?name=world", receivedPath.get());

        assertEquals("round-trip-1", response.getRequestId());
        assertEquals(200, response.getStatusCode());
        assertNull(response.getError());
        assertEquals("{\"greeting\":\"hi\"}", response.getBody());
        // java.net.http's HttpHeaders normalizes header names to lowercase.
        assertEquals("application/json", findHeaderIgnoreCase(response, "Content-Type"));
        assertEquals("app-value", findHeaderIgnoreCase(response, "X-App-Header"));
        assertTrue(response.getDurationMs() >= 0);
    }

    @Test
    void forward_postRequest_deliversBodyAndCustomHeaders() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedCustomHeader = new AtomicReference<>();
        server.createContext("/webhook", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedCustomHeader.set(exchange.getRequestHeaders().getFirst("X-Signature"));
            exchange.sendResponseHeaders(201, -1);
        });
        server.start();

        LocalForwarder forwarder = new LocalForwarder(port);
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("round-trip-2")
                .method("POST")
                .path("/webhook")
                .headers(Map.of("X-Signature", "sig-abc", "Content-Type", "application/json"))
                .body("{\"event\":\"order.created\"}")
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        assertEquals("{\"event\":\"order.created\"}", receivedBody.get());
        assertEquals("sig-abc", receivedCustomHeader.get());
        assertEquals(201, response.getStatusCode());
        assertEquals("round-trip-2", response.getRequestId());
    }

    @Test
    void forward_pathWithoutLeadingSlash_isNormalized() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        server.createContext("/bare", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        LocalForwarder forwarder = new LocalForwarder(port);
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("round-trip-3")
                .method("GET")
                .path("bare") // no leading slash
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        assertEquals("/bare", receivedPath.get());
        assertEquals(200, response.getStatusCode());
    }

    @Test
    void forward_nonSuccessStatus_isPassedThroughUnchanged() throws Exception {
        // Unlike the workflow HttpNodeExecutor, LocalForwarder must NOT translate a
        // non-2xx local response into a synthetic error — the whole point of a tunnel
        // is that the caller sees exactly what the local app returned.
        server.createContext("/broken", exchange -> {
            byte[] resp = "nope".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        LocalForwarder forwarder = new LocalForwarder(port);
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("round-trip-4")
                .method("GET")
                .path("/broken")
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        assertEquals(404, response.getStatusCode());
        assertEquals("nope", response.getBody());
        assertNull(response.getError());
    }

    @Test
    void forward_restrictedHeaders_areStripped() throws Exception {
        AtomicReference<String> receivedHostHeader = new AtomicReference<>();
        server.createContext("/headers", exchange -> {
            receivedHostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        LocalForwarder forwarder = new LocalForwarder(port);
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("round-trip-5")
                .method("GET")
                .path("/headers")
                .headers(Map.of("Host", "evil.example", "X-Real-Header", "kept"))
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelResponseMessage response = forwarder.forward(request);

        // The forwarder must talk to localhost:<port>, not honor a spoofed Host header.
        assertNotEquals("evil.example", receivedHostHeader.get());
        assertEquals(200, response.getStatusCode());
    }

    @Test
    void forward_putAndDeleteMethods_reachServerWithCorrectVerb() throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        server.createContext("/resource", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
        });
        server.start();

        LocalForwarder forwarder = new LocalForwarder(port);

        TunnelResponseMessage putResponse = forwarder.forward(TunnelRequestMessage.builder()
                .requestId("round-trip-6a").method("PUT").path("/resource").body("{}")
                .timestampMs(System.currentTimeMillis()).build());
        assertEquals("PUT", receivedMethod.get());
        assertEquals(204, putResponse.getStatusCode());

        TunnelResponseMessage deleteResponse = forwarder.forward(TunnelRequestMessage.builder()
                .requestId("round-trip-6b").method("DELETE").path("/resource")
                .timestampMs(System.currentTimeMillis()).build());
        assertEquals("DELETE", receivedMethod.get());
        assertEquals(204, deleteResponse.getStatusCode());
    }

    private static String findHeaderIgnoreCase(TunnelResponseMessage response, String name) {
        if (response.getHeaders() == null) return null;
        return response.getHeaders().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
