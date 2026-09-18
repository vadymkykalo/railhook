package com.webhook.platform.cli.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.tunnel.TunnelMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketTunnelClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aResponseThatFitsGoesOutWithItsBytes() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', (byte) 0xff, 0x00};
        TunnelResponseMessage response = TunnelResponseMessage.builder()
                .requestId("r1").statusCode(200).headers(Map.of("content-type", "image/png"))
                .rawBody(png, StandardCharsets.UTF_8).build();

        String json = WebSocketTunnelClient.responseJson(mapper, response);

        TunnelResponseMessage sent = mapper.readValue(json, TunnelMessage.class).getResponse();
        assertArrayEquals(png, sent.bodyBytes());
        assertNull(sent.getError());
    }

    // The server closes the socket over a message beyond its limit, which ended the whole tunnel;
    // one oversized local response now fails that one request instead.
    @Test
    void aResponseTooLargeForTheServerBecomesA502() throws Exception {
        byte[] large = new byte[600 * 1024];
        Arrays.fill(large, (byte) 0xff);
        TunnelResponseMessage response = TunnelResponseMessage.builder()
                .type("TUNNEL_RESPONSE").requestId("r2").statusCode(200)
                .rawBody(large, StandardCharsets.UTF_8).build();

        String json = WebSocketTunnelClient.responseJson(mapper, response);

        assertTrue(json.length() <= WebSocketTunnelClient.SERVER_MAX_MESSAGE_CHARS);
        TunnelResponseMessage sent = mapper.readValue(json, TunnelMessage.class).getResponse();
        assertEquals("r2", sent.getRequestId());
        assertEquals(502, sent.getStatusCode());
        assertNotNull(sent.getError());
        assertNull(sent.getBody());
    }
}
