package com.webhook.platform.common.dto.tunnel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A provider signs the bytes it sent, so a tunnelled body must arrive as those bytes, old peers included. */
class TunnelBodyTest {

    private static final byte[] NOT_UTF8 = {(byte) 0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xff, (byte) 0xfe,
            0x00, (byte) 0x80, (byte) 0xc3};

    private final ObjectMapper mapper = new ObjectMapper();
    // What an installed CLI reads with: its own copy of the classes, unknown fields ignored.
    private final ObjectMapper lenient = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void aTextBodyTravelsAsTheStringAloneAsItAlwaysDid() throws Exception {
        byte[] form = "text=a%20b&token=X%2fY".getBytes(StandardCharsets.US_ASCII);
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .headers(Map.of("Content-Type", "application/x-www-form-urlencoded"))
                .rawBody(form, StandardCharsets.UTF_8)
                .build();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(TunnelMessage.tunnelRequest(request)));

        assertEquals("text=a%20b&token=X%2fY", json.get("request").get("body").asText());
        assertFalse(json.get("request").has("bodyBase64"), "no second copy when the string is exact");
        TunnelRequestMessage read = mapper.treeToValue(json.get("request"), TunnelRequestMessage.class);
        assertArrayEquals(form, read.bodyBytes());
    }

    @Test
    void aBinaryRequestBodyArrivesByteForByte() throws Exception {
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .headers(Map.of("Content-Type", "application/octet-stream", "Content-Encoding", "gzip"))
                .rawBody(NOT_UTF8, StandardCharsets.UTF_8)
                .build();

        String json = mapper.writeValueAsString(TunnelMessage.tunnelRequest(request));
        TunnelRequestMessage read = mapper.readValue(json, TunnelMessage.class).getRequest();

        assertArrayEquals(NOT_UTF8, read.bodyBytes());
        assertEquals("gzip", read.getHeaders().get("Content-Encoding"));
    }

    @Test
    void anInstalledCliStillReadsTheStringBody() throws Exception {
        TunnelRequestMessage request = TunnelRequestMessage.builder()
                .requestId("r1")
                .rawBody(NOT_UTF8, StandardCharsets.UTF_8)
                .build();

        String json = mapper.writeValueAsString(TunnelMessage.tunnelRequest(request));
        JsonNode body = lenient.readTree(json).get("request").get("body");

        assertEquals(new String(NOT_UTF8, StandardCharsets.UTF_8), body.asText());
        assertTrue(lenient.readTree(json).get("request").has("bodyBase64"));
    }

    @Test
    void aMessageFromAnOlderServerFallsBackToTheString() throws Exception {
        String old = "{\"type\":\"TUNNEL_REQUEST\",\"request\":{\"requestId\":\"r1\",\"method\":\"POST\","
                + "\"headers\":{\"content-type\":\"text/plain; charset=ISO-8859-1\"},\"body\":\"café\"}}";

        TunnelRequestMessage read = mapper.readValue(old, TunnelMessage.class).getRequest();

        assertArrayEquals("café".getBytes(StandardCharsets.ISO_8859_1), read.bodyBytes());
    }

    @Test
    void aMessageWithoutABodyHasNoBytes() throws Exception {
        TunnelRequestMessage read = mapper.readValue(
                "{\"requestId\":\"r1\",\"method\":\"GET\"}", TunnelRequestMessage.class);

        assertNull(read.bodyBytes());
        assertNull(TunnelRequestMessage.builder().rawBody(null, StandardCharsets.UTF_8).build().getBody());
    }

    @Test
    void aBinaryResponseBodyArrivesByteForByte() throws Exception {
        TunnelResponseMessage response = TunnelResponseMessage.builder()
                .statusCode(200)
                .headers(Map.of("content-type", "image/png"))
                .rawBody(NOT_UTF8, TunnelBody.charsetOf(Map.of("content-type", "image/png")))
                .build();

        String json = mapper.writeValueAsString(TunnelMessage.tunnelResponse(response));
        TunnelResponseMessage read = mapper.readValue(json, TunnelMessage.class).getResponse();

        assertArrayEquals(NOT_UTF8, read.bodyBytes());
        assertEquals(new String(NOT_UTF8, StandardCharsets.UTF_8), read.getBody(), "string kept for old servers");
    }

    @Test
    void aResponseFromAnInstalledCliFallsBackToTheString() throws Exception {
        String old = "{\"requestId\":\"r1\",\"statusCode\":200,"
                + "\"headers\":{\"content-type\":\"application/json\"},\"body\":\"{\\\"ok\\\":\\\"ü\\\"}\"}";

        TunnelResponseMessage read = mapper.readValue(old, TunnelResponseMessage.class);

        assertArrayEquals("{\"ok\":\"ü\"}".getBytes(StandardCharsets.UTF_8), read.bodyBytes());
    }

    @Test
    void anEmptyResponseBodyIsEmptyNotAbsent() {
        TunnelResponseMessage response = TunnelResponseMessage.builder()
                .rawBody(new byte[0], StandardCharsets.UTF_8).build();

        assertEquals("", response.getBody());
        assertArrayEquals(new byte[0], response.bodyBytes());
    }

    @Test
    void theCharsetIsTheOneContentTypeNames() {
        assertEquals(StandardCharsets.UTF_8, TunnelBody.charsetOf(null));
        assertEquals(StandardCharsets.UTF_8, TunnelBody.charsetOf(Map.of("Content-Type", "application/json")));
        assertEquals(StandardCharsets.ISO_8859_1,
                TunnelBody.charsetOf(Map.of("CONTENT-TYPE", "text/plain; Charset=\"iso-8859-1\"")));
        assertEquals(Charset.forName("windows-1251"),
                TunnelBody.charsetOf(Map.of("content-type", "text/xml;charset=windows-1251;foo=bar")));
        assertEquals(StandardCharsets.UTF_8, TunnelBody.charsetOf(Map.of("content-type", "text/plain; charset=nope")));
    }

    @Test
    void aFieldFromANewerPeerIsIgnored() throws Exception {
        TunnelMessage read = mapper.readValue("{\"type\":\"TUNNEL_RESPONSE\",\"somethingNew\":1,"
                + "\"response\":{\"requestId\":\"r1\",\"statusCode\":204,\"alsoNew\":true}}", TunnelMessage.class);

        assertEquals(204, read.getResponse().getStatusCode());
        TunnelRequestMessage request = mapper.readValue("{\"requestId\":\"r1\",\"future\":[1]}",
                TunnelRequestMessage.class);
        assertEquals("r1", request.getRequestId());
    }

    @Test
    void aMessageLeavesItsUnsetFieldsOutOfTheJson() throws Exception {
        String json = mapper.writeValueAsString(TunnelMessage.heartbeat());

        assertTrue(json.contains("\"type\":\"HEARTBEAT\""));
        assertFalse(json.contains("\"request\""));
        assertFalse(json.contains("\"response\""));
        assertFalse(json.contains("\"tunnelUrl\""));
        assertFalse(json.contains("\"error\""));
    }
}
