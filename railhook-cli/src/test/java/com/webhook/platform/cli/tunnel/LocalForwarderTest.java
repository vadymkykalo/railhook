package com.webhook.platform.cli.tunnel;

import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.common.dto.tunnel.TunnelBody;
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

    // ─── Bodies are bytes: what the provider sent is what the local app gets ────
    // The app checks the provider's signature over those bytes, so a body re-encoded on the
    // way (a form's %20 turned into +, a gzip body through a UTF-8 String) fails it.

    private static final byte[] NOT_UTF8 = {(byte) 0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xff, (byte) 0xfe,
            0x00, (byte) 0x80, (byte) 0xc3};

    @Test
    void forward_formBody_reachesTheLocalAppByteForByte() throws Exception {
        byte[] form = "text=a%20b&token=X%2fY".getBytes(StandardCharsets.US_ASCII);
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> receivedType = new AtomicReference<>();
        server.createContext("/slack", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            receivedType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        Map<String, String> headers = Map.of("Content-Type", "application/x-www-form-urlencoded");
        new LocalForwarder(port).forward(TunnelRequestMessage.builder()
                .requestId("bytes-1").method("POST").path("/slack").headers(headers)
                .rawBody(form, TunnelBody.charsetOf(headers))
                .build());

        assertArrayEquals(form, received.get());
        assertEquals("application/x-www-form-urlencoded", receivedType.get());
    }

    @Test
    void forward_binaryBody_reachesTheLocalAppByteForByte() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> receivedEncoding = new AtomicReference<>();
        server.createContext("/upload", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            receivedEncoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            exchange.sendResponseHeaders(204, -1);
        });
        server.start();

        Map<String, String> headers = Map.of("Content-Type", "application/json", "Content-Encoding", "gzip");
        new LocalForwarder(port).forward(TunnelRequestMessage.builder()
                .requestId("bytes-2").method("POST").path("/upload").headers(headers)
                .rawBody(NOT_UTF8, TunnelBody.charsetOf(headers))
                .build());

        assertArrayEquals(NOT_UTF8, received.get());
        assertEquals("gzip", receivedEncoding.get());
    }

    // An older server sends the string alone; it was decoded with the charset Content-Type
    // names, and goes out encoded with the same one.
    @Test
    void forward_stringBodyFromAnOlderServer_isEncodedWithItsDeclaredCharset() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        server.createContext("/legacy", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        new LocalForwarder(port).forward(TunnelRequestMessage.builder()
                .requestId("bytes-3").method("PUT").path("/legacy")
                .headers(Map.of("Content-Type", "text/plain; charset=ISO-8859-1"))
                .body("café")
                .build());

        assertArrayEquals("café".getBytes(StandardCharsets.ISO_8859_1), received.get());
    }

    @Test
    void forward_binaryResponse_comesBackByteForByte() throws Exception {
        server.createContext("/logo.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, NOT_UTF8.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(NOT_UTF8);
            }
        });
        server.start();

        TunnelResponseMessage response = new LocalForwarder(port).forward(TunnelRequestMessage.builder()
                .requestId("bytes-4").method("GET").path("/logo.png").build());

        assertEquals(200, response.getStatusCode());
        assertArrayEquals(NOT_UTF8, response.bodyBytes());
        // An older server reads only the string, which is what it always got.
        assertEquals(new String(NOT_UTF8, StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    void forward_textResponse_travelsAsTheStringAlone() throws Exception {
        byte[] text = "{\"ok\":\"café\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, text.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(text);
            }
        });
        server.start();

        TunnelResponseMessage response = new LocalForwarder(port).forward(TunnelRequestMessage.builder()
                .requestId("bytes-5").method("GET").path("/json").build());

        assertEquals("{\"ok\":\"café\"}", response.getBody());
        assertNull(response.getBodyBase64());
        assertArrayEquals(text, response.bodyBytes());
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
