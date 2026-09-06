package com.webhook.platform.api.tenancy;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Runs every callback of a WebSocket handler under {@link TenantContext#SYSTEM}.
 *
 * <p>A WebSocket connection is not an HTTP request that {@code TenantContextFilter} sees, and its
 * callbacks arrive on container threads long after the handshake, so without this the tunnel hub
 * would hit {@link TenantNotResolvedException} on its first query.
 *
 * <p>A decorator rather than {@code @SystemTenant} on the handler's methods: Spring AOP proxies
 * only public methods, and {@code handleTextMessage} — where the tunnel does most of its work —
 * is protected. An annotation there would have looked like a declaration and enforced nothing,
 * which is exactly the failure mode this avoids.
 *
 * <p>The hub reads and writes {@code tunnel_sessions} across organizations by design: which
 * organization a session belongs to is a property of the row, discovered from the token the CLI
 * presented, not of the connection.
 */
public class SystemTenantWebSocketHandlerDecorator extends WebSocketHandlerDecorator {

    public SystemTenantWebSocketHandlerDecorator(WebSocketHandler delegate) {
        super(delegate);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        TenantContext.callAsSystemChecked(() -> {
            super.afterConnectionEstablished(session);
            return null;
        });
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        TenantContext.callAsSystemChecked(() -> {
            super.handleMessage(session, message);
            return null;
        });
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        TenantContext.callAsSystemChecked(() -> {
            super.handleTransportError(session, exception);
            return null;
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        TenantContext.callAsSystemChecked(() -> {
            super.afterConnectionClosed(session, closeStatus);
            return null;
        });
    }
}
