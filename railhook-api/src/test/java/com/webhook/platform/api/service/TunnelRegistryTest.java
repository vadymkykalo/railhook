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

        tunnelRegistry.unregister("slug-1", session);
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
        tunnelRegistry.completeRequest("ws-none", requestId, TunnelResponseMessage.builder()
                .requestId(requestId)
                .statusCode(200)
                .body("OK")
                .build());

        // No exception — just logs a warning about no pending request
        assertEquals(0, tunnelRegistry.pendingRequestCount());
    }

    // A request already sent down a socket that then closes has nobody left to answer it. It used
    // to wait out the full 30s and come back as a 504 "timed out" — and, across instances, the
    // caller gave up at 25s while the owner still waited, so the local app could get the request
    // after the provider had already been told it failed and was resending it.
    @Test
    void aRequestInFlightWhenItsTunnelDisconnectsFailsAtOnce() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-inflight");
        when(session.isOpen()).thenReturn(true);
        tunnelRegistry.register("slug-inflight", session);

        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("req-inflight").method("POST").path("/hook").build();
        CompletableFuture<TunnelResponseMessage> answer = CompletableFuture.supplyAsync(
                () -> tunnelRegistry.forwardRequest("slug-inflight", request));
        long deadline = System.currentTimeMillis() + 5_000;
        while (tunnelRegistry.pendingRequestCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, tunnelRegistry.pendingRequestCount(), "the request must be in flight first");

        tunnelRegistry.unregister("slug-inflight", session);

        TunnelResponseMessage response = answer.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(response, "a disconnect is an answer, not a timeout");
        assertEquals(502, response.getStatusCode());
        assertEquals("tunnel_disconnected", response.getError());
        assertEquals(0, tunnelRegistry.pendingRequestCount());
    }

    // The CLI reconnects before the old socket's close callback has run — an API restart does
    // exactly this. Unregistering by slug alone then removed the new session and failed its work.
    @Test
    void theCloseOfAReplacedSessionLeavesTheNewOneAndItsRequestsAlone() throws Exception {
        WebSocketSession old = mock(WebSocketSession.class);
        when(old.getId()).thenReturn("ws-old");
        when(old.isOpen()).thenReturn(true);
        WebSocketSession replacement = mock(WebSocketSession.class);
        when(replacement.getId()).thenReturn("ws-new");
        when(replacement.isOpen()).thenReturn(true);

        tunnelRegistry.register("slug-reconnect", old);
        tunnelRegistry.register("slug-reconnect", replacement);

        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("req-on-new").method("POST").path("/hook").build();
        CompletableFuture<TunnelResponseMessage> answer = CompletableFuture.supplyAsync(
                () -> tunnelRegistry.forwardRequest("slug-reconnect", request));
        long deadline = System.currentTimeMillis() + 5_000;
        while (tunnelRegistry.pendingRequestCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        tunnelRegistry.unregister("slug-reconnect", old);

        assertTrue(tunnelRegistry.isActive("slug-reconnect"), "the replacement session is still the tunnel");
        assertFalse(answer.isDone(), "a request on the replacement session must not be failed");
        tunnelRegistry.completeRequest("ws-new", "req-on-new", TunnelResponseMessage.builder()
                .requestId("req-on-new").statusCode(200).body("ok").build());
        assertEquals(200, answer.get(2, java.util.concurrent.TimeUnit.SECONDS).getStatusCode());
    }

    @Test
    void disconnectClosesTheSocketHoldingTheSlug() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-closed-by-api");
        when(session.isOpen()).thenReturn(true);
        tunnelRegistry.register("slug-deleted", session);

        tunnelRegistry.disconnect("slug-deleted");

        verify(session).close(any(org.springframework.web.socket.CloseStatus.class));
    }

    // Request ids travel to the CLI in the clear, so any socket that learned one could answer a
    // request that was sent down another tunnel.
    @Test
    void aResponseFromAnotherSocketDoesNotAnswerTheRequest() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-owner");
        when(session.isOpen()).thenReturn(true);
        tunnelRegistry.register("slug-owned", session);

        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("req-owned").method("POST").path("/hook").build();
        CompletableFuture<TunnelResponseMessage> answer = CompletableFuture.supplyAsync(
                () -> tunnelRegistry.forwardRequest("slug-owned", request));
        long deadline = System.currentTimeMillis() + 5_000;
        while (tunnelRegistry.pendingRequestCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        tunnelRegistry.completeRequest("ws-intruder", "req-owned", TunnelResponseMessage.builder()
                .requestId("req-owned").statusCode(200).body("forged").build());
        Thread.sleep(100);
        assertFalse(answer.isDone(), "a response from a different socket must be ignored");

        tunnelRegistry.completeRequest("ws-owner", "req-owned", TunnelResponseMessage.builder()
                .requestId("req-owned").statusCode(200).body("real").build());
        assertEquals("real", answer.get(2, java.util.concurrent.TimeUnit.SECONDS).getBody());
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

        tunnelRegistry.unregister("slug-a", session1);
        assertEquals(1, tunnelRegistry.activeCount());
    }
}
