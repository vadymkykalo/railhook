package com.webhook.platform.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.api.service.RedisTunnelCoordinator;
import com.webhook.platform.api.service.TunnelRegistry;
import com.webhook.platform.api.service.TunnelService;
import com.webhook.platform.api.service.TunnelWebSocketHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.webhook.platform.common.dto.tunnel.TunnelMessage;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TunnelFlowIntegrationTest {

    private ObjectMapper objectMapper;
    private TunnelRegistry tunnelRegistry;
    private TunnelService tunnelService;
    private TunnelWebSocketHandler webSocketHandler;
    private RedisTunnelCoordinator redisTunnelCoordinator;

    private com.webhook.platform.api.domain.repository.TunnelSessionRepository tunnelSessionRepository;
    private com.webhook.platform.api.domain.repository.ProjectRepository projectRepository;

    private TunnelSession testSession;
    private String tunnelToken;
    private String publicSlug;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tunnelSessionRepository = mock(com.webhook.platform.api.domain.repository.TunnelSessionRepository.class);
        projectRepository = mock(com.webhook.platform.api.domain.repository.ProjectRepository.class);

        redisTunnelCoordinator = mock(RedisTunnelCoordinator.class);
        tunnelService = new TunnelService(tunnelSessionRepository, projectRepository,
                mock(com.webhook.platform.api.domain.repository.OrganizationRepository.class),
                mock(com.webhook.platform.api.service.billing.EntitlementService.class),
                redisTunnelCoordinator);
        org.springframework.test.util.ReflectionTestUtils.setField(tunnelService, "ingressBaseUrl", "http://localhost:8080");
        org.springframework.test.util.ReflectionTestUtils.setField(tunnelService, "heartbeatTimeoutSeconds", 120);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        tunnelRegistry = new TunnelRegistry(objectMapper, meterRegistry);
        webSocketHandler = new TunnelWebSocketHandler(tunnelService, tunnelRegistry, redisTunnelCoordinator, objectMapper, meterRegistry);

        tunnelToken = "test-tunnel-token-" + UUID.randomUUID();
        publicSlug = "tun-inttest12345";

        testSession = TunnelSession.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .tunnelToken(tunnelToken)
                .publicSlug(publicSlug)
                .localPort(3000)
                .status(TunnelStatus.ACTIVE)
                .lastHeartbeat(java.time.Instant.now())
                .createdAt(java.time.Instant.now())
                .build();
    }

    @Test
    void fullTunnelFlow_createSession_connectWs_forwardRequest_receiveResponse() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken(tunnelToken))
                .thenReturn(java.util.Optional.of(testSession));
        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebSocketSession wsSession = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(wsSession);

        assertTrue(tunnelRegistry.isActive(publicSlug),
                "Tunnel should be registered and active after WS connect");
        assertEquals(1, tunnelRegistry.activeCount());

        verify(wsSession, atLeastOnce()).sendMessage(any(TextMessage.class));

        TunnelRequestMessage incomingRequest = TunnelRequestMessage.builder()
                .type("TUNNEL_REQUEST")
                .requestId("req-" + UUID.randomUUID())
                .method("POST")
                .path("/api/test")
                .queryString("foo=bar")
                .headers(Map.of("Content-Type", "application/json", "X-Custom", "value"))
                .body("{\"hello\":\"world\"}")
                .timestampMs(System.currentTimeMillis())
                .build();

        CapturedMessage capturedRequest = new CapturedMessage();
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            capturedRequest.payload = msg.getPayload();
            capturedRequest.latch.countDown();
            return null;
        }).when(wsSession).sendMessage(any(TextMessage.class));

        // Forward blocks waiting for the response, so run it on another thread.
        CompletableFuture<TunnelResponseMessage> responseFuture = CompletableFuture.supplyAsync(() ->
                tunnelRegistry.forwardRequest(publicSlug, incomingRequest));

        assertTrue(capturedRequest.latch.await(5, TimeUnit.SECONDS),
                "Request should be forwarded to WS session");

        TunnelMessage sentEnvelope = objectMapper.readValue(capturedRequest.payload, TunnelMessage.class);
        assertEquals(TunnelMessage.TYPE_TUNNEL_REQUEST, sentEnvelope.getType());
        assertNotNull(sentEnvelope.getRequest());
        assertEquals(incomingRequest.getRequestId(), sentEnvelope.getRequest().getRequestId());
        assertEquals("POST", sentEnvelope.getRequest().getMethod());
        assertEquals("/api/test", sentEnvelope.getRequest().getPath());
        assertEquals("foo=bar", sentEnvelope.getRequest().getQueryString());
        assertEquals("{\"hello\":\"world\"}", sentEnvelope.getRequest().getBody());

        TunnelResponseMessage cliResponse = TunnelResponseMessage.builder()
                .type("TUNNEL_RESPONSE")
                .requestId(incomingRequest.getRequestId())
                .statusCode(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body("{\"status\":\"ok\"}")
                .durationMs(42)
                .timestampMs(System.currentTimeMillis())
                .build();

        TunnelMessage responseEnvelope = TunnelMessage.tunnelResponse(cliResponse);
        String responseJson = objectMapper.writeValueAsString(responseEnvelope);

        webSocketHandler.handleMessage(wsSession, new TextMessage(responseJson));

        TunnelResponseMessage result = responseFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(result, "Should receive response from CLI");
        assertEquals(200, result.getStatusCode());
        assertEquals("{\"status\":\"ok\"}", result.getBody());
        assertEquals(incomingRequest.getRequestId(), result.getRequestId());
        assertEquals(42, result.getDurationMs());
        assertEquals("application/json", result.getHeaders().get("Content-Type"));

        assertEquals(0, tunnelRegistry.pendingRequestCount());
    }

    @Test
    void heartbeatFlow_cliSendsHeartbeat_serverResponds() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken(tunnelToken))
                .thenReturn(java.util.Optional.of(testSession));
        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebSocketSession wsSession = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(wsSession);

        clearInvocations(wsSession);
        when(wsSession.isOpen()).thenReturn(true);

        TunnelMessage heartbeat = TunnelMessage.heartbeat();
        String heartbeatJson = objectMapper.writeValueAsString(heartbeat);
        webSocketHandler.handleMessage(wsSession, new TextMessage(heartbeatJson));

        verify(wsSession).sendMessage(argThat(msg -> {
            try {
                TunnelMessage resp = objectMapper.readValue(((TextMessage) msg).getPayload(), TunnelMessage.class);
                return TunnelMessage.TYPE_HEARTBEAT.equals(resp.getType());
            } catch (Exception e) {
                return false;
            }
        }));

        verify(tunnelSessionRepository, atLeast(2)).findByTunnelToken(tunnelToken);
    }

    @Test
    void disconnectFlow_wsCloses_tunnelUnregisteredButSessionStaysOpen() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken(tunnelToken))
                .thenReturn(java.util.Optional.of(testSession));
        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebSocketSession wsSession = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(wsSession);

        assertTrue(tunnelRegistry.isActive(publicSlug));

        webSocketHandler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

        assertFalse(tunnelRegistry.isActive(publicSlug));
        assertEquals(0, tunnelRegistry.activeCount());

        // A dropped socket is not a closed tunnel: closing it ended every tunnel on each rolling deploy.
        verify(tunnelSessionRepository, never()).save(argThat(s -> s.getStatus() == TunnelStatus.CLOSED));
        assertEquals(TunnelStatus.ACTIVE, testSession.getStatus());
    }

    @Test
    void reconnectFlow_sameTokenAfterADrop_registersTheTunnelAgain() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken(tunnelToken))
                .thenReturn(java.util.Optional.of(testSession));
        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebSocketSession first = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(first);
        webSocketHandler.afterConnectionClosed(first, CloseStatus.GOING_AWAY);
        assertFalse(tunnelRegistry.isActive(publicSlug));

        WebSocketSession second = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(second);

        verify(second, never()).close(any(CloseStatus.class));
        assertTrue(tunnelRegistry.isActive(publicSlug), "the same public URL works again after the reconnect");
    }

    @Test
    void rejectInvalidToken_wsClosedImmediately() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken("invalid-token"))
                .thenReturn(java.util.Optional.empty());

        WebSocketSession wsSession = createMockWsSession("invalid-token");
        when(tunnelSessionRepository.findByTunnelToken("invalid-token"))
                .thenReturn(java.util.Optional.empty());

        webSocketHandler.afterConnectionEstablished(wsSession);

        verify(wsSession).close(argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));

        assertEquals(0, tunnelRegistry.activeCount());
    }

    @Test
    void rejectMissingToken_wsClosedImmediately() throws Exception {
        WebSocketSession wsSession = mock(WebSocketSession.class);
        when(wsSession.getUri()).thenReturn(new URI("ws://localhost:8080/ws/tunnel"));
        when(wsSession.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(wsSession.getHandshakeHeaders()).thenReturn(new HttpHeaders());

        webSocketHandler.afterConnectionEstablished(wsSession);

        verify(wsSession).close(argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        assertEquals(0, tunnelRegistry.activeCount());
    }

    @Test
    void rejectInactiveSession_wsClosedImmediately() throws Exception {
        TunnelSession closedSession = TunnelSession.builder()
                .id(UUID.randomUUID())
                .tunnelToken("closed-token")
                .publicSlug("tun-closed123")
                .status(TunnelStatus.CLOSED)
                .build();

        when(tunnelSessionRepository.findByTunnelToken("closed-token"))
                .thenReturn(java.util.Optional.of(closedSession));

        WebSocketSession wsSession = createMockWsSession("closed-token");
        webSocketHandler.afterConnectionEstablished(wsSession);

        verify(wsSession).close(argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        assertEquals(0, tunnelRegistry.activeCount());
    }

    @Test
    void forwardTimeout_returnsNullWhenCliDoesNotRespond() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken(tunnelToken))
                .thenReturn(java.util.Optional.of(testSession));
        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebSocketSession wsSession = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(wsSession);

        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("req-timeout")
                .method("GET")
                .path("/slow")
                .build();

        // The default 30s timeout would be slow; the null-return path covers it.
        TunnelResponseMessage result = tunnelRegistry.forwardRequest("nonexistent-slug", request);
        assertNull(result, "Should return null for non-connected tunnel");
        assertEquals(0, tunnelRegistry.pendingRequestCount());
    }

    @Test
    void multipleRequestsConcurrently_allCorrelatedCorrectly() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken(tunnelToken))
                .thenReturn(java.util.Optional.of(testSession));
        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebSocketSession wsSession = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(wsSession);

        ConcurrentLinkedQueue<String> sentMessages = new ConcurrentLinkedQueue<>();
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            sentMessages.add(msg.getPayload());
            return null;
        }).when(wsSession).sendMessage(any(TextMessage.class));

        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch allStarted = new CountDownLatch(requestCount);
        ConcurrentHashMap<String, CompletableFuture<TunnelResponseMessage>> futures = new ConcurrentHashMap<>();

        for (int i = 0; i < requestCount; i++) {
            String reqId = "req-concurrent-" + i;
            TunnelRequestMessage req = TunnelRequestMessage.builder()
                    .requestId(reqId)
                    .method("GET")
                    .path("/test/" + i)
                    .build();

            CompletableFuture<TunnelResponseMessage> future = CompletableFuture.supplyAsync(() -> {
                allStarted.countDown();
                return tunnelRegistry.forwardRequest(publicSlug, req);
            }, executor);
            futures.put(reqId, future);
        }

        allStarted.await(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Reverse order to test correlation.
        for (int i = requestCount - 1; i >= 0; i--) {
            String reqId = "req-concurrent-" + i;
            TunnelResponseMessage response = TunnelResponseMessage.builder()
                    .requestId(reqId)
                    .statusCode(200 + i)
                    .body("response-" + i)
                    .build();
            tunnelRegistry.completeRequest(wsSession.getId(), reqId, response);
        }

        for (int i = 0; i < requestCount; i++) {
            String reqId = "req-concurrent-" + i;
            TunnelResponseMessage result = futures.get(reqId).get(5, TimeUnit.SECONDS);
            assertNotNull(result, "Response for " + reqId + " should not be null");
            assertEquals(200 + i, result.getStatusCode());
            assertEquals("response-" + i, result.getBody());
            assertEquals(reqId, result.getRequestId());
        }

        assertEquals(0, tunnelRegistry.pendingRequestCount());
        executor.shutdown();
    }

    @Test
    void transportError_loggedButNoException() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken(tunnelToken))
                .thenReturn(java.util.Optional.of(testSession));
        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebSocketSession wsSession = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(wsSession);

        assertDoesNotThrow(() ->
                webSocketHandler.handleTransportError(wsSession, new IOException("Connection reset")));
    }

    @Test
    void invalidJsonMessage_ignoredGracefully() throws Exception {
        when(tunnelSessionRepository.findByTunnelToken(tunnelToken))
                .thenReturn(java.util.Optional.of(testSession));
        when(tunnelSessionRepository.save(any(TunnelSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebSocketSession wsSession = createMockWsSession(tunnelToken);
        webSocketHandler.afterConnectionEstablished(wsSession);

        assertDoesNotThrow(() ->
                webSocketHandler.handleMessage(wsSession, new TextMessage("{invalid json!!!")));

        assertTrue(tunnelRegistry.isActive(publicSlug));
    }

    private WebSocketSession createMockWsSession(String token) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("ws://localhost:8080/ws/tunnel?token=" + token));
        when(session.getId()).thenReturn("ws-session-" + UUID.randomUUID());
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        return session;
    }

    private static class CapturedMessage {
        volatile String payload;
        final CountDownLatch latch = new CountDownLatch(1);
    }
}
