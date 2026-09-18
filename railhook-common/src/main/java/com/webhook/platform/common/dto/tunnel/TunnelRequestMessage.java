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

/**
 * Message sent from backend to CLI through WebSocket when an incoming
 * HTTP request arrives at the tunnel's public URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TunnelRequestMessage {

    private String type;
    private String requestId;
    private String method;
    private String path;
    private String queryString;
    private Map<String, String> headers;
    /** The body as text, populated as it always was; the only body an older peer reads. */
    private String body;
    /** The exact bytes, present only when {@link #body} cannot carry them. See {@link TunnelBody}. */
    private String bodyBase64;
    private long timestampMs;

    /** The body's bytes as they were sent: {@code bodyBase64} when present, else the string. */
    @JsonIgnore
    public byte[] bodyBytes() {
        return TunnelBody.bytes(body, bodyBase64, headers);
    }

    public static class TunnelRequestMessageBuilder {

        /**
         * Sets {@code body} and, when that string would not carry these bytes exactly,
         * {@code bodyBase64}. {@code charset} is the one the {@code Content-Type} header of
         * this message names — {@link TunnelBody#charsetOf} — so the reader can encode it back.
         */
        public TunnelRequestMessageBuilder rawBody(byte[] raw, Charset charset) {
            this.body = TunnelBody.text(raw, charset);
            this.bodyBase64 = TunnelBody.base64IfLossy(raw, charset);
            return this;
        }
    }
}
