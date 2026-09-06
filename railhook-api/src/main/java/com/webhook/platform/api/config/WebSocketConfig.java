package com.webhook.platform.api.config;

import com.webhook.platform.api.service.TunnelWebSocketHandler;
import com.webhook.platform.api.tenancy.SystemTenantWebSocketHandlerDecorator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TunnelWebSocketHandler tunnelWebSocketHandler;
    private final String[] allowedOrigins;

    public WebSocketConfig(
            TunnelWebSocketHandler tunnelWebSocketHandler,
            @Value("${tunnel.ws.allowed-origins:${CORS_ALLOWED_ORIGINS:http://localhost:5173}}") String allowedOriginsCsv) {
        this.tunnelWebSocketHandler = tunnelWebSocketHandler;
        this.allowedOrigins = allowedOriginsCsv.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new SystemTenantWebSocketHandlerDecorator(tunnelWebSocketHandler), "/ws/tunnel")
                .setAllowedOrigins(allowedOrigins);
    }
}
