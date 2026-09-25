package com.webhook.platform.common.dto.tunnel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Envelope for tunnel WebSocket messages; {@code type} says which fields are set. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TunnelMessage {

    public static final String TYPE_TUNNEL_REQUEST = "TUNNEL_REQUEST";
    public static final String TYPE_TUNNEL_RESPONSE = "TUNNEL_RESPONSE";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_TUNNEL_REGISTERED = "TUNNEL_REGISTERED";
    public static final String TYPE_ERROR = "ERROR";

    private String type;

    private TunnelRequestMessage request;

    private TunnelResponseMessage response;

    private String tunnelUrl;
    private String tunnelId;

    private String error;

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
