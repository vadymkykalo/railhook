package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.tunnel.TunnelMessage;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * In-memory registry of active WebSocket tunnel connections.
 * Maps tunnel slug → WebSocketSession for request forwarding,
 * and manages pending request → CompletableFuture for response correlation.
 */
@Slf4j
@Component
public class TunnelRegistry {

    private final ObjectMapper objectMapper;

    /** slug → WebSocketSession */
    private final ConcurrentHashMap<String, WebSocketSession> activeTunnels = new ConcurrentHashMap<>();

    /** requestId → CompletableFuture<TunnelResponseMessage> */
    private final ConcurrentHashMap<String, CompletableFuture<TunnelResponseMessage>> pendingRequests = new ConcurrentHashMap<>();

    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final int MAX_PENDING_REQUESTS = 1000;

    public TunnelRegistry(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        Gauge.builder("tunnel_active_connections", activeTunnels, ConcurrentHashMap::size)
                .description("Number of active tunnel WebSocket connections on this instance")
                .register(meterRegistry);
        Gauge.builder("tunnel_pending_requests", pendingRequests, ConcurrentHashMap::size)
                .description("Number of pending tunnel forwarding requests on this instance")
                .register(meterRegistry);
    }

    public void register(String slug, WebSocketSession session) {
        activeTunnels.put(slug, session);
        log.info("Tunnel registered: slug={}, sessionId={}", slug, session.getId());
    }

    public void unregister(String slug) {
        activeTunnels.remove(slug);
        log.info("Tunnel unregistered: slug={}", slug);
    }

    public boolean isActive(String slug) {
        WebSocketSession session = activeTunnels.get(slug);
        return session != null && session.isOpen();
    }

    /**
     * Forward an HTTP request through the tunnel and wait for the CLI's response.
     * Returns null if the tunnel is not connected or the request times out.
     */
    public TunnelResponseMessage forwardRequest(String slug, TunnelRequestMessage request) {
        WebSocketSession session = activeTunnels.get(slug);
        if (session == null || !session.isOpen()) {
            log.warn("No active tunnel for slug={}", slug);
            return null;
        }

        if (pendingRequests.size() >= MAX_PENDING_REQUESTS) {
            log.warn("Too many pending tunnel requests, rejecting for slug={}", slug);
            return null;
        }

        String requestId = request.getRequestId();
        CompletableFuture<TunnelResponseMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            TunnelMessage message = TunnelMessage.tunnelRequest(request);
            String json = objectMapper.writeValueAsString(message);

            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }

            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Tunnel request timed out: requestId={}, slug={}", requestId, slug);
            return null;
        } catch (Exception e) {
            log.error("Error forwarding tunnel request: requestId={}, slug={}", requestId, slug, e);
            return null;
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    /**
     * Called when the CLI sends back a response through WebSocket.
     */
    public void completeRequest(String requestId, TunnelResponseMessage response) {
        CompletableFuture<TunnelResponseMessage> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(response);
        } else {
            log.warn("No pending request found for requestId={}", requestId);
        }
    }

    public void sendMessage(String slug, TunnelMessage message) throws IOException {
        WebSocketSession session = activeTunnels.get(slug);
        if (session != null && session.isOpen()) {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    public int activeCount() {
        return activeTunnels.size();
    }

    public int pendingRequestCount() {
        return pendingRequests.size();
    }
}
