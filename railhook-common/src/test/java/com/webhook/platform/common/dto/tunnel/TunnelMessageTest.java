package com.webhook.platform.common.dto.tunnel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TunnelMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeHeartbeat() throws Exception {
        TunnelMessage msg = TunnelMessage.heartbeat();
        String json = objectMapper.writeValueAsString(msg);

        assertTrue(json.contains("\"type\":\"HEARTBEAT\""));
        assertTrue(json.contains("\"timestampMs\""));

        TunnelMessage deserialized = objectMapper.readValue(json, TunnelMessage.class);
        assertEquals(TunnelMessage.TYPE_HEARTBEAT, deserialized.getType());
        assertTrue(deserialized.getTimestampMs() > 0);
    }

    @Test
    void shouldSerializeRegistered() throws Exception {
        TunnelMessage msg = TunnelMessage.registered("tunnel-123", "https://example.com/tunnel/abc");
        String json = objectMapper.writeValueAsString(msg);

        TunnelMessage deserialized = objectMapper.readValue(json, TunnelMessage.class);
        assertEquals(TunnelMessage.TYPE_TUNNEL_REGISTERED, deserialized.getType());
        assertEquals("tunnel-123", deserialized.getTunnelId());
        assertEquals("https://example.com/tunnel/abc", deserialized.getTunnelUrl());
    }

    @Test
    void shouldSerializeError() throws Exception {
        TunnelMessage msg = TunnelMessage.error("something went wrong");
        String json = objectMapper.writeValueAsString(msg);

        TunnelMessage deserialized = objectMapper.readValue(json, TunnelMessage.class);
        assertEquals(TunnelMessage.TYPE_ERROR, deserialized.getType());
        assertEquals("something went wrong", deserialized.getError());
    }

    @Test
    void shouldSerializeTunnelRequest() throws Exception {
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .type("TUNNEL_REQUEST")
                .requestId("req-001")
                .method("POST")
                .path("/webhook/receive")
                .queryString("source=github")
                .headers(Map.of("Content-Type", "application/json", "X-Hub-Signature", "sha256=abc"))
                .body("{\"action\":\"push\"}")
                .timestampMs(1700000000000L)
                .build();

        TunnelMessage msg = TunnelMessage.tunnelRequest(request);
        String json = objectMapper.writeValueAsString(msg);

        TunnelMessage deserialized = objectMapper.readValue(json, TunnelMessage.class);
        assertEquals(TunnelMessage.TYPE_TUNNEL_REQUEST, deserialized.getType());
        assertNotNull(deserialized.getRequest());
        assertEquals("req-001", deserialized.getRequest().getRequestId());
        assertEquals("POST", deserialized.getRequest().getMethod());
        assertEquals("/webhook/receive", deserialized.getRequest().getPath());
        assertEquals("source=github", deserialized.getRequest().getQueryString());
        assertEquals("{\"action\":\"push\"}", deserialized.getRequest().getBody());
        assertEquals("application/json", deserialized.getRequest().getHeaders().get("Content-Type"));
    }

    @Test
    void shouldSerializeTunnelResponse() throws Exception {
        TunnelResponseMessage response = TunnelResponseMessage.builder()
                .type("TUNNEL_RESPONSE")
                .requestId("req-001")
                .statusCode(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body("{\"status\":\"ok\"}")
                .durationMs(42)
                .timestampMs(1700000000042L)
                .build();

        TunnelMessage msg = TunnelMessage.tunnelResponse(response);
        String json = objectMapper.writeValueAsString(msg);

        TunnelMessage deserialized = objectMapper.readValue(json, TunnelMessage.class);
        assertEquals(TunnelMessage.TYPE_TUNNEL_RESPONSE, deserialized.getType());
        assertNotNull(deserialized.getResponse());
        assertEquals("req-001", deserialized.getResponse().getRequestId());
        assertEquals(200, deserialized.getResponse().getStatusCode());
        assertEquals("{\"status\":\"ok\"}", deserialized.getResponse().getBody());
        assertEquals(42, deserialized.getResponse().getDurationMs());
    }

    @Test
    void shouldExcludeNullFieldsFromJson() throws Exception {
        TunnelMessage msg = TunnelMessage.heartbeat();
        String json = objectMapper.writeValueAsString(msg);

        assertFalse(json.contains("\"request\""));
        assertFalse(json.contains("\"response\""));
        assertFalse(json.contains("\"tunnelUrl\""));
        assertFalse(json.contains("\"error\""));
    }

    @Test
    void shouldHandleResponseWithError() throws Exception {
        TunnelResponseMessage response = TunnelResponseMessage.builder()
                .type("TUNNEL_RESPONSE")
                .requestId("req-002")
                .statusCode(502)
                .error("Connection refused: localhost:3000 is not reachable")
                .durationMs(5)
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelMessage msg = TunnelMessage.tunnelResponse(response);
        String json = objectMapper.writeValueAsString(msg);

        TunnelMessage deserialized = objectMapper.readValue(json, TunnelMessage.class);
        assertEquals(502, deserialized.getResponse().getStatusCode());
        assertEquals("Connection refused: localhost:3000 is not reachable", deserialized.getResponse().getError());
        assertNull(deserialized.getResponse().getBody());
    }
}
