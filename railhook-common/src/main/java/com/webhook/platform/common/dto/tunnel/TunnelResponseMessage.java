package com.webhook.platform.common.dto.tunnel;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.Charset;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TunnelResponseMessage {

    private String type;
    private String requestId;
    private int statusCode;
    private Map<String, String> headers;
    /** Always set; older peers read only this. */
    private String body;
    /** Only when {@link #body} cannot carry the exact bytes. */
    private String bodyBase64;
    private String error;
    private long durationMs;
    private long timestampMs;

    @JsonIgnore
    public byte[] bodyBytes() {
        return TunnelBody.bytes(body, bodyBase64, headers);
    }

    public static class TunnelResponseMessageBuilder {

        /** {@code charset} must be the one this message's Content-Type names. */
        public TunnelResponseMessageBuilder rawBody(byte[] raw, Charset charset) {
            this.body = TunnelBody.text(raw, charset);
            this.bodyBase64 = TunnelBody.base64IfLossy(raw, charset);
            return this;
        }
    }
}
