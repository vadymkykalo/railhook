package com.webhook.platform.cli.transport;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.webhook.platform.common.dto.tunnel.TunnelMessage;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * WebSocket client that maintains a persistent connection to the backend tunnel endpoint.
 * Receives forwarded HTTP requests and sends back responses after local forwarding.
 */
public class WebSocketTunnelClient {

    private static final Logger log = LoggerFactory.getLogger(WebSocketTunnelClient.class);
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int MAX_MESSAGE_SIZE = 1024 * 1024; // 1MB

    // Exponential backoff config
    private static final long BASE_DELAY_MS = 1_000;      // 1s initial
    private static final long MAX_DELAY_MS  = 120_000;     // 2min cap
    private static final int  MAX_RECONNECT_ATTEMPTS = 50; // generous limit with exp backoff
    private static final double JITTER_FACTOR = 0.5;       // ±50% randomization

    private final String wsUrl;
    private final String tunnelToken;
    private final int localPort;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final ThreadLocalRandom random = ThreadLocalRandom.current();

    private Session wsSession;
    private ScheduledFuture<?> heartbeatFuture;
    private Consumer<TunnelRequestMessage> requestHandler;
    private Consumer<String> onRegistered;
    private Runnable onDisconnected;
    private Consumer<String> onReconnecting;
    private int reconnectAttempts = 0;

    public WebSocketTunnelClient(String wsUrl, String tunnelToken, int localPort) {
        this.wsUrl = wsUrl;
        this.tunnelToken = tunnelToken;
        this.localPort = localPort;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "tunnel-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void onRequest(Consumer<TunnelRequestMessage> handler) {
        this.requestHandler = handler;
    }

    public void onRegistered(Consumer<String> handler) {
        this.onRegistered = handler;
    }

    public void onDisconnected(Runnable handler) {
        this.onDisconnected = handler;
    }

    public void onReconnecting(Consumer<String> handler) {
        this.onReconnecting = handler;
    }

    public void connect() {
        running.set(true);
        doConnect();
    }

    public void disconnect() {
        running.set(false);
        scheduler.shutdownNow();
        closeSession();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void sendResponse(TunnelResponseMessage response) {
        if (wsSession == null || !wsSession.isOpen()) {
            log.warn("Cannot send response — WebSocket not connected");
            return;
        }
        try {
            TunnelMessage message = TunnelMessage.tunnelResponse(response);
            String json = objectMapper.writeValueAsString(message);
            wsSession.getBasicRemote().sendText(json);
        } catch (IOException e) {
            log.error("Failed to send tunnel response: {}", e.getMessage());
        }
    }

    private void doConnect() {
        try {
            String fullUrl = wsUrl + "/ws/tunnel";
            log.debug("Connecting to {}", fullUrl);

            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(MAX_MESSAGE_SIZE);

            // Pass token via Sec-WebSocket-Protocol header (not query param)
            // to avoid leaking it in server access logs and proxy logs
            ClientEndpointConfig endpointConfig = ClientEndpointConfig.Builder.create()
                    .preferredSubprotocols(List.of("tunnel-token." + tunnelToken))
                    .build();

            wsSession = container.connectToServer(new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig config) {
                    connected.set(true);
                    reconnectAttempts = 0;
                    log.debug("WebSocket connected");

                    session.addMessageHandler(String.class, WebSocketTunnelClient.this::handleMessage);
                    startHeartbeat();
                }

                @Override
                public void onClose(Session session, CloseReason closeReason) {
                    connected.set(false);
                    stopHeartbeat();
                    log.info("WebSocket closed: {}", closeReason);
                    if (onDisconnected != null) onDisconnected.run();

                    if (running.get()) {
                        // Don't retry on auth/policy failures
                        int code = closeReason.getCloseCode().getCode();
                        if (code == CloseReason.CloseCodes.VIOLATED_POLICY.getCode()
                                || code == CloseReason.CloseCodes.CANNOT_ACCEPT.getCode()) {
                            log.error("Server rejected connection (code {}): {}. Not retrying.",
                                    code, closeReason.getReasonPhrase());
                            running.set(false);
                            return;
                        }
                        scheduleReconnect();
                    }
                }

                @Override
                public void onError(Session session, Throwable error) {
                    connected.set(false);
                    log.error("WebSocket error: {}", error.getMessage());
                }
            }, endpointConfig, URI.create(fullUrl));

        } catch (Exception e) {
            connected.set(false);
            log.error("WebSocket connection failed: {}", e.getMessage());
            if (running.get()) scheduleReconnect();
        }
    }

    private void handleMessage(String text) {
        try {
            TunnelMessage message = objectMapper.readValue(text, TunnelMessage.class);

            switch (message.getType()) {
                case TunnelMessage.TYPE_TUNNEL_REGISTERED:
                    log.info("Tunnel registered: url={}", message.getTunnelUrl());
                    if (onRegistered != null) onRegistered.accept(message.getTunnelUrl());
                    break;

                case TunnelMessage.TYPE_TUNNEL_REQUEST:
                    if (requestHandler != null && message.getRequest() != null) {
                        requestHandler.accept(message.getRequest());
                    }
                    break;

                case TunnelMessage.TYPE_HEARTBEAT:
                    log.trace("Heartbeat received");
                    break;

                case TunnelMessage.TYPE_ERROR:
                    log.error("Server error: {}", message.getError());
                    break;

                default:
                    log.debug("Unknown message type: {}", message.getType());
            }
        } catch (Exception e) {
            log.error("Failed to handle message: {}", e.getMessage());
        }
    }

    private void sendHeartbeat() {
        if (wsSession != null && wsSession.isOpen()) {
            try {
                TunnelMessage heartbeat = TunnelMessage.heartbeat();
                String json = objectMapper.writeValueAsString(heartbeat);
                wsSession.getBasicRemote().sendText(json);
            } catch (IOException e) {
                log.warn("Failed to send heartbeat: {}", e.getMessage());
            }
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::sendHeartbeat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("Max reconnect attempts ({}) reached. Giving up.", MAX_RECONNECT_ATTEMPTS);
            running.set(false);
            return;
        }

        reconnectAttempts++;
        long delayMs = computeBackoffMs(reconnectAttempts);
        long delaySec = delayMs / 1000;

        String msg = String.format("Reconnecting in %ds (attempt %d/%d)",
                delaySec, reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
        log.info(msg);
        if (onReconnecting != null) onReconnecting.accept(msg);

        scheduler.schedule(this::doConnect, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Exponential backoff with jitter: base * 2^(attempt-1), capped, then randomized ±50%.
     */
    long computeBackoffMs(int attempt) {
        long exponential = BASE_DELAY_MS * (1L << Math.min(attempt - 1, 20));
        long capped = Math.min(exponential, MAX_DELAY_MS);
        double jitter = 1.0 - JITTER_FACTOR + (random.nextDouble() * JITTER_FACTOR * 2);
        return (long) (capped * jitter);
    }

    private void closeSession() {
        if (wsSession != null && wsSession.isOpen()) {
            try {
                wsSession.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client disconnect"));
            } catch (IOException e) {
                log.debug("Error closing WebSocket: {}", e.getMessage());
            }
        }
        connected.set(false);
    }
}
