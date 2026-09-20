package com.webhook.platform.common.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one worked example of the JavaScript contract, loaded from
 * {@code transform/contract-example.json}.
 *
 * <p>It exists so that the two places a transformation can run — the api's preview and the
 * worker's delivery — can be checked against the same script, the same input and the same
 * expected bytes, without either module depending on the other. {@code TransformParityTest} on
 * each side loads this and asserts the same result; a call site that starts doing its own thing
 * turns one of them red.
 *
 * <p>The same script is the worked example on the transformations documentation page. Changing
 * it is changing what is published.
 */
public final class TransformContractExample {

    private static final String RESOURCE = "transform/contract-example.json";

    private final JsonNode root;

    private TransformContractExample(JsonNode root) {
        this.root = root;
    }

    public static TransformContractExample load(ObjectMapper objectMapper) {
        try (InputStream in = TransformContractExample.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE + " on the classpath");
            }
            return new TransformContractExample(objectMapper.readTree(in));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
    }

    public String script() {
        return root.get("script").asText();
    }

    public String inputJson() {
        return root.get("input").toString();
    }

    /** The expected body, compact and key-ordered exactly as the engine returns it. */
    public String expectedPayloadJson() {
        return root.get("expectedPayload").toString();
    }

    public Map<String, String> expectedHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        root.get("expectedHeaders").properties()
                .forEach(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        return headers;
    }

    /** The delivery context both call sites must hand the engine for the result to match. */
    public TransformRequest request() {
        return TransformRequest.builder()
                .payload(inputJson())
                .eventType(root.get("eventType").asText())
                .eventId(root.get("eventId").asText())
                .timestamp(Instant.parse(root.get("timestamp").asText()))
                .direction("OUTGOING")
                .url(root.get("url").asText())
                .headers(Map.of())
                .build();
    }
}
