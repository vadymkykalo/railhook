package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import com.webhook.platform.common.security.UrlValidator;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * HTTP Request node — makes an outbound HTTP call to a URL the workflow's author typed.
 *
 * <p>That makes it the one node a user can aim, so it carries both halves of the SSRF defence:
 * {@link UrlValidator} refuses a URL that resolves somewhere private, and
 * {@link SsrfProtectionCustomizer} re-checks the address the connector actually dials. The
 * second is not redundant — validating and connecting are two separate resolutions of the same
 * name, and a name can move between them. This class previously claimed to "reuse SSRF
 * protection" while building a bare client, so only the first half was true.
 */
@Component
@Slf4j
public class HttpNodeExecutor implements NodeExecutor {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final boolean allowPrivateIps;
    private final List<String> allowedHosts;

    public HttpNodeExecutor(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts) {
        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(
                        SsrfProtectionCustomizer.apply(HttpClient.create(), allowPrivateIps, allowedHosts)))
                .build();
        this.objectMapper = objectMapper;
        this.allowPrivateIps = allowPrivateIps;
        this.allowedHosts = allowedHosts;
    }

    @Override
    public String getType() {
        return "http";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        try {
            String url = getTextOrNull(nodeConfig, "url");
            if (url == null || url.isBlank()) {
                return StepResult.failed("HTTP node: url is required");
            }

            // Passing the configured allow-list rather than null: it is an exemption list, so
            // null did not weaken the check — it meant an operator who allow-listed an internal
            // host for every other outbound path could not reach it from a workflow node. One
            // WEBHOOK_ALLOWED_HOSTS should mean one thing everywhere.
            UrlValidator.validateWebhookUrl(url, allowPrivateIps, allowedHosts);

            String method = getTextOrDefault(nodeConfig, "method", "POST");
            int timeoutSeconds = nodeConfig.has("timeout") ? nodeConfig.get("timeout").asInt(30) : 30;
            timeoutSeconds = Math.max(1, Math.min(60, timeoutSeconds));

            // Build request body
            String body;
            if (nodeConfig.has("body") && !nodeConfig.get("body").isNull()) {
                JsonNode bodyNode = nodeConfig.get("body");
                body = bodyNode.isTextual() ? bodyNode.textValue() : bodyNode.toString();
            } else {
                body = input != null ? input.toString() : "{}";
            }

            // Execute HTTP call
            WebClient.RequestBodySpec requestSpec = webClient.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("User-Agent", "HookflowWorkflow/1.0");

            // Custom headers
            if (nodeConfig.has("headers") && nodeConfig.get("headers").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> headers = nodeConfig.get("headers").fields();
                while (headers.hasNext()) {
                    Map.Entry<String, JsonNode> h = headers.next();
                    String key = h.getKey().toLowerCase();
                    if (!key.equals("host") && !key.equals("content-length") && !key.equals("transfer-encoding")) {
                        requestSpec.header(h.getKey(), h.getValue().asText());
                    }
                }
            }

            long start = System.currentTimeMillis();
            var responseSpec = requestSpec
                    .bodyValue(body)
                    .retrieve()
                    .toEntity(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            long durationMs = System.currentTimeMillis() - start;

            if (responseSpec == null) {
                return StepResult.failed("HTTP node: no response received");
            }

            int statusCode = responseSpec.getStatusCode().value();
            String responseBody = responseSpec.getBody();

            ObjectNode output = objectMapper.createObjectNode();
            output.put("statusCode", statusCode);
            output.put("durationMs", durationMs);
            if (responseBody != null) {
                try {
                    output.set("body", objectMapper.readTree(responseBody));
                } catch (Exception e) {
                    output.put("body", responseBody);
                }
            }

            // Build response headers
            ObjectNode respHeaders = objectMapper.createObjectNode();
            HttpHeaders headers = responseSpec.getHeaders();
            headers.forEach((key, values) -> {
                if (values != null && !values.isEmpty()) {
                    respHeaders.put(key, values.get(0));
                }
            });
            output.set("headers", respHeaders);

            if (statusCode >= 200 && statusCode < 300) {
                return StepResult.success(output);
            } else {
                return StepResult.failed("HTTP " + statusCode + ": " + (responseBody != null ? responseBody.substring(0, Math.min(500, responseBody.length())) : ""));
            }
        } catch (UrlValidator.InvalidUrlException e) {
            return StepResult.failed("SSRF blocked: " + e.getMessage());
        } catch (Exception e) {
            log.error("HTTP node execution failed: {}", e.getMessage(), e);
            return StepResult.failed("HTTP error: " + e.getMessage());
        }
    }

    private String getTextOrNull(JsonNode node, String field) {
        return node.has(field) && node.get(field).isTextual() ? node.get(field).textValue() : null;
    }

    private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        String val = getTextOrNull(node, field);
        return val != null ? val : defaultValue;
    }
}
