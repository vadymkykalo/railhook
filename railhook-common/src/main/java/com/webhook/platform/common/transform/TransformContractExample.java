package com.webhook.platform.common.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One script, input and expected output that {@code TransformParityTest} in both api and worker
 * run, so preview and delivery cannot drift. The same script is published in the docs.
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

    /** Compact and key-ordered exactly as the engine returns it. */
    public String expectedPayloadJson() {
        return root.get("expectedPayload").toString();
    }

    public Map<String, String> expectedHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        root.get("expectedHeaders").properties()
                .forEach(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        return headers;
    }

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
