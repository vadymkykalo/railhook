package com.webhook.platform.api.tenancy;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * WebSocket callbacks arrive on container threads with no request scope. A decorator rather than
 * {@code @SystemTenant} because Spring AOP proxies only public methods, and
 * {@code handleTextMessage} is protected.
 *
 * <p>System scope is correct here: a tunnel session's organization comes from the token the CLI
 * presented, not from the connection.
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
