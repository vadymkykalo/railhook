package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.common.dto.tunnel.TunnelMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;

@Slf4j
@Component
public class TunnelWebSocketHandler extends TextWebSocketHandler {

    private static final String SUBPROTOCOL_PREFIX = "tunnel-token.";

    private final TunnelService tunnelService;
    private final TunnelRegistry tunnelRegistry;
    private final RedisTunnelCoordinator redisTunnelCoordinator;
    private final ObjectMapper objectMapper;
    private final Counter wsConnectCounter;
    private final Counter wsDisconnectCounter;
    private final Counter wsAuthFailureCounter;

    public TunnelWebSocketHandler(TunnelService tunnelService,
                                  TunnelRegistry tunnelRegistry,
                                  RedisTunnelCoordinator redisTunnelCoordinator,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.tunnelService = tunnelService;
        this.tunnelRegistry = tunnelRegistry;
        this.redisTunnelCoordinator = redisTunnelCoordinator;
        this.objectMapper = objectMapper;
        this.wsConnectCounter = Counter.builder("tunnel_ws_connections_total")
                .tag("event", "connect")
                .description("Tunnel WebSocket connection events")
                .register(meterRegistry);
        this.wsDisconnectCounter = Counter.builder("tunnel_ws_connections_total")
                .tag("event", "disconnect")
                .description("Tunnel WebSocket disconnection events")
                .register(meterRegistry);
        this.wsAuthFailureCounter = Counter.builder("tunnel_ws_connections_total")
                .tag("event", "auth_failure")
                .description("Tunnel WebSocket auth failure events")
                .register(meterRegistry);
    }

    private static final String ATTR_TUNNEL_TOKEN = "tunnelToken";
    private static final String ATTR_SLUG = "slug";
    private static final int MAX_MESSAGE_SIZE = 1024 * 1024; // 1MB

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(MAX_MESSAGE_SIZE);

        String tunnelToken = extractTokenFromSubprotocol(session);
        if (tunnelToken == null) {
            tunnelToken = extractQueryParam(session, "token");
        }
        if (tunnelToken == null || tunnelToken.isBlank()) {
            log.warn("WebSocket connection without tunnel token, closing");
            wsAuthFailureCounter.increment();
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Missing tunnel token"));
            return;
        }

        TunnelSession tunnelSession;
        try {
            tunnelSession = tunnelService.getByToken(tunnelToken);
        } catch (Exception e) {
            log.warn("Invalid tunnel token on WS connect: {}", e.getMessage());
            wsAuthFailureCounter.increment();
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid tunnel token"));
            return;
        }

        if (tunnelSession.getStatus() != TunnelStatus.ACTIVE) {
            log.warn("Tunnel session not active: {}", tunnelSession.getId());
            wsAuthFailureCounter.increment();
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Tunnel session not active"));
            return;
        }

        String slug = tunnelSession.getPublicSlug();
        session.getAttributes().put(ATTR_TUNNEL_TOKEN, tunnelToken);
        session.getAttributes().put(ATTR_SLUG, slug);

        tunnelRegistry.register(slug, session);
        redisTunnelCoordinator.registerSlug(slug);
        tunnelService.heartbeat(tunnelToken);

        String tunnelUrl = tunnelService.buildPublicUrl(slug);
        TunnelMessage registered = TunnelMessage.registered(tunnelSession.getId().toString(), tunnelUrl);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(registered)));

        wsConnectCounter.increment();
        log.info("Tunnel WS connected: slug={}, sessionId={}", slug, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String slug = (String) session.getAttributes().get(ATTR_SLUG);
        String tunnelToken = (String) session.getAttributes().get(ATTR_TUNNEL_TOKEN);

        if (slug == null || tunnelToken == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Not authenticated"));
            return;
        }

        TunnelMessage tunnelMessage;
        try {
            tunnelMessage = objectMapper.readValue(message.getPayload(), TunnelMessage.class);
        } catch (Exception e) {
            log.warn("Invalid tunnel message from slug={}: {}", slug, e.getMessage());
            return;
        }

        switch (tunnelMessage.getType()) {
            case TunnelMessage.TYPE_HEARTBEAT:
                tunnelService.heartbeat(tunnelToken);
                redisTunnelCoordinator.refreshSlug(slug);
                session.sendMessage(new TextMessage(
                        objectMapper.writeValueAsString(TunnelMessage.heartbeat())));
                break;

            case TunnelMessage.TYPE_TUNNEL_RESPONSE:
                if (tunnelMessage.getResponse() != null && tunnelMessage.getResponse().getRequestId() != null) {
                    tunnelRegistry.completeRequest(
                            tunnelMessage.getResponse().getRequestId(),
                            tunnelMessage.getResponse());
                }
                break;

            default:
                log.debug("Unhandled tunnel message type: {} from slug={}", tunnelMessage.getType(), slug);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String slug = (String) session.getAttributes().get(ATTR_SLUG);
        String tunnelToken = (String) session.getAttributes().get(ATTR_TUNNEL_TOKEN);

        if (slug != null) {
            tunnelRegistry.unregister(slug);
            redisTunnelCoordinator.unregisterSlug(slug);
        }
        if (tunnelToken != null) {
            tunnelService.closeSession(tunnelToken);
        }

        wsDisconnectCounter.increment();
        log.info("Tunnel WS disconnected: slug={}, status={}", slug, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String slug = (String) session.getAttributes().get(ATTR_SLUG);
        log.error("Tunnel WS transport error: slug={}", slug, exception);
    }

    /**
     * Extract token from Sec-WebSocket-Protocol header.
     * Client sends: Sec-WebSocket-Protocol: tunnel-token.{TOKEN}
     * Server echoes the subprotocol to complete the handshake.
     */
    private String extractTokenFromSubprotocol(WebSocketSession session) {
        List<String> protocols = session.getHandshakeHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null) return null;
        for (String protocol : protocols) {
            for (String part : protocol.split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(SUBPROTOCOL_PREFIX)) {
                    return trimmed.substring(SUBPROTOCOL_PREFIX.length());
                }
            }
        }
        return null;
    }

    private String extractQueryParam(WebSocketSession session, String paramName) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String param : uri.getQuery().split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && pair[0].equals(paramName)) {
                return pair[1];
            }
        }
        return null;
    }
}
