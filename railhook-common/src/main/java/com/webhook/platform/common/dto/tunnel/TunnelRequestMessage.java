package com.webhook.platform.common.dto.tunnel;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Message sent from backend to CLI through WebSocket when an incoming
 * HTTP request arrives at the tunnel's public URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TunnelRequestMessage {

    private String type;
    private String requestId;
    private String method;
    private String path;
    private String queryString;
    private Map<String, String> headers;
    private String body;
    private long timestampMs;
}
