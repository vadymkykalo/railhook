package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.tunnel.TunnelMessage;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * In-memory registry of active WebSocket tunnel connections.
 * Maps tunnel slug → WebSocketSession for request forwarding,
 * and manages pending request → CompletableFuture for response correlation.
 */
@Slf4j
@Component
public class TunnelRegistry {

    /** The error a request carries when the socket it was sent down closed before an answer came. */
    public static final String DISCONNECTED = "tunnel_disconnected";

    private final ObjectMapper objectMapper;

    /** slug → WebSocketSession */
    private final ConcurrentHashMap<String, WebSocketSession> activeTunnels = new ConcurrentHashMap<>();

    /** requestId → the request waiting for the CLI, and which socket it went down */
    private final ConcurrentHashMap<String, Pending> pendingRequests = new ConcurrentHashMap<>();

    private record Pending(String sessionId, CompletableFuture<TunnelResponseMessage> answer) {
    }

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

    /**
     * The given socket has closed. Fails every request still waiting on it, and removes the slug
     * only while it still names this socket.
     *
     * <p>Both halves are about a socket, not a slug. A request sent down a closed socket has nobody
     * left to answer it, and waiting out the timeout made the caller a 504 thirty seconds later —
     * after the provider's own timeout, so it resent a request the local app might still receive.
     * And a CLI that reconnects before the old socket's close callback runs, as it does across an
     * API restart, has already put its new socket under the same slug; removing by slug alone took
     * the live tunnel down with the dead one.
     *
     * @return whether the slug was removed, i.e. this socket was still the tunnel
     */
    public boolean unregister(String slug, WebSocketSession session) {
        boolean removed = activeTunnels.remove(slug, session);
        int failed = failRequestsOn(session.getId());
        log.info("Tunnel socket closed: slug={}, sessionId={}, unregistered={}, inFlightFailed={}",
                slug, session.getId(), removed, failed);
        return removed;
    }

    private int failRequestsOn(String sessionId) {
        int failed = 0;
        for (var entry : pendingRequests.entrySet()) {
            Pending pending = entry.getValue();
            if (pending.sessionId().equals(sessionId) && pendingRequests.remove(entry.getKey(), pending)) {
                pending.answer().complete(TunnelResponseMessage.builder()
                        .requestId(entry.getKey())
                        .statusCode(502)
                        .error(DISCONNECTED)
                        .build());
                failed++;
            }
        }
        return failed;
    }

    public boolean isActive(String slug) {
        WebSocketSession session = activeTunnels.get(slug);
        return session != null && session.isOpen();
    }

    /**
     * Forward an HTTP request through the tunnel and wait for the CLI's response.
     * Returns null if the tunnel is not connected or the request times out; a response carrying
     * {@link #DISCONNECTED} if the tunnel's socket closed while the request was waiting.
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
        pendingRequests.put(requestId, new Pending(session.getId(), future));

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
     * Called when a CLI sends back a response through its WebSocket. Only the socket the request
     * was sent down may answer it: the request id is all a response names, and any other tunnel
     * that learned one could otherwise answer a request meant for someone else's machine.
     */
    public void completeRequest(String socketId, String requestId, TunnelResponseMessage response) {
        Pending pending = pendingRequests.get(requestId);
        if (pending == null) {
            log.warn("No pending request found for requestId={}", requestId);
            return;
        }
        if (!pending.sessionId().equals(socketId)) {
            log.warn("Ignoring response for requestId={} from socket {}: it was sent down {}",
                    requestId, socketId, pending.sessionId());
            return;
        }
        if (pendingRequests.remove(requestId, pending)) {
            pending.answer().complete(response);
        }
    }

    /**
     * Ends the tunnel on this instance, if its socket is here: the slug stops resolving at once,
     * rather than when the close callback gets round to it, and the socket is closed.
     */
    public void disconnect(String slug) {
        WebSocketSession session = activeTunnels.get(slug);
        if (session == null) {
            return;
        }
        unregister(slug, session);
        try {
            session.close(CloseStatus.NORMAL.withReason("Tunnel session closed"));
        } catch (IOException e) {
            log.warn("Failed to close tunnel socket: slug={}, error={}", slug, e.getMessage());
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
