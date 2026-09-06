package com.webhook.platform.common.dto.tunnel;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Message sent from CLI back to backend through WebSocket
 * containing the local application's HTTP response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TunnelResponseMessage {

    private String type;
    private String requestId;
    private int statusCode;
    private Map<String, String> headers;
    private String body;
    private String error;
    private long durationMs;
    private long timestampMs;
}
