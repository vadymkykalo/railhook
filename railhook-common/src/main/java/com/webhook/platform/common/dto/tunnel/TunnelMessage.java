package com.webhook.platform.common.dto.tunnel;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envelope for all tunnel WebSocket messages.
 * The {@code type} field determines the payload structure.
 *
 * <ul>
 *   <li>{@code TUNNEL_REQUEST} — backend → CLI, contains {@link TunnelRequestMessage}</li>
 *   <li>{@code TUNNEL_RESPONSE} — CLI → backend, contains {@link TunnelResponseMessage}</li>
 *   <li>{@code HEARTBEAT} — bidirectional keep-alive ping</li>
 *   <li>{@code TUNNEL_REGISTERED} — backend → CLI, tunnel is active</li>
 *   <li>{@code ERROR} — error notification in either direction</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TunnelMessage {

    public static final String TYPE_TUNNEL_REQUEST = "TUNNEL_REQUEST";
    public static final String TYPE_TUNNEL_RESPONSE = "TUNNEL_RESPONSE";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_TUNNEL_REGISTERED = "TUNNEL_REGISTERED";
    public static final String TYPE_ERROR = "ERROR";

    private String type;

    /** Populated for TUNNEL_REQUEST messages */
    private TunnelRequestMessage request;

    /** Populated for TUNNEL_RESPONSE messages */
    private TunnelResponseMessage response;

    /** Populated for TUNNEL_REGISTERED messages */
    private String tunnelUrl;
    private String tunnelId;

    /** Populated for ERROR messages */
    private String error;

    /** Populated for HEARTBEAT messages */
    private long timestampMs;

    public static TunnelMessage heartbeat() {
        return TunnelMessage.builder()
                .type(TYPE_HEARTBEAT)
                .timestampMs(System.currentTimeMillis())
                .build();
    }

    public static TunnelMessage registered(String tunnelId, String tunnelUrl) {
        return TunnelMessage.builder()
                .type(TYPE_TUNNEL_REGISTERED)
                .tunnelId(tunnelId)
                .tunnelUrl(tunnelUrl)
                .build();
    }

    public static TunnelMessage error(String error) {
        return TunnelMessage.builder()
                .type(TYPE_ERROR)
                .error(error)
                .build();
    }

    public static TunnelMessage tunnelRequest(TunnelRequestMessage request) {
        return TunnelMessage.builder()
                .type(TYPE_TUNNEL_REQUEST)
                .request(request)
                .build();
    }

    public static TunnelMessage tunnelResponse(TunnelResponseMessage response) {
        return TunnelMessage.builder()
                .type(TYPE_TUNNEL_RESPONSE)
                .response(response)
                .build();
    }
}
