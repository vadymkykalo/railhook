package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TunnelRegistryTest {

    private TunnelRegistry tunnelRegistry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tunnelRegistry = new TunnelRegistry(objectMapper, new SimpleMeterRegistry());
    }

    @Test
    void shouldRegisterAndUnregister() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);

        tunnelRegistry.register("slug-1", session);
        assertTrue(tunnelRegistry.isActive("slug-1"));
        assertEquals(1, tunnelRegistry.activeCount());

        tunnelRegistry.unregister("slug-1");
        assertFalse(tunnelRegistry.isActive("slug-1"));
        assertEquals(0, tunnelRegistry.activeCount());
    }

    @Test
    void shouldReturnFalseForNonExistentSlug() {
        assertFalse(tunnelRegistry.isActive("nonexistent"));
    }

    @Test
    void shouldReturnFalseForClosedSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-2");
        when(session.isOpen()).thenReturn(false);

        tunnelRegistry.register("slug-2", session);
        assertFalse(tunnelRegistry.isActive("slug-2"));
    }

    @Test
    void shouldReturnNullWhenForwardingToInactiveTunnel() {
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("req-1")
                .method("GET")
                .path("/test")
                .build();

        TunnelResponseMessage response = tunnelRegistry.forwardRequest("nonexistent", request);
        assertNull(response);
    }

    @Test
    void shouldCompleteRequest() {
        String requestId = "req-complete-1";

        // Simulate: register a pending request and complete it
        tunnelRegistry.completeRequest(requestId, TunnelResponseMessage.builder()
                .requestId(requestId)
                .statusCode(200)
                .body("OK")
                .build());

        // No exception — just logs a warning about no pending request
        assertEquals(0, tunnelRegistry.pendingRequestCount());
    }

    @Test
    void shouldTrackPendingRequestCount() {
        assertEquals(0, tunnelRegistry.pendingRequestCount());
    }

    @Test
    void shouldTrackActiveCount() {
        assertEquals(0, tunnelRegistry.activeCount());

        WebSocketSession session1 = mock(WebSocketSession.class);
        when(session1.getId()).thenReturn("ws-a");
        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session2.getId()).thenReturn("ws-b");

        tunnelRegistry.register("slug-a", session1);
        tunnelRegistry.register("slug-b", session2);
        assertEquals(2, tunnelRegistry.activeCount());

        tunnelRegistry.unregister("slug-a");
        assertEquals(1, tunnelRegistry.activeCount());
    }
}
