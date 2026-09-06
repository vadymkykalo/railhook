package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack node — sends a message to a Slack webhook URL.
 * Supports {{field.path}} placeholders in the message text.
 */
@Component
@Slf4j
public class SlackNodeExecutor implements NodeExecutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)}}");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public SlackNodeExecutor(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "slack";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        try {
            String webhookUrl = nodeConfig.has("webhookUrl") ? nodeConfig.get("webhookUrl").asText() : null;
            if (webhookUrl == null || webhookUrl.isBlank()) {
                return StepResult.failed("Slack node: webhookUrl is required");
            }

            if (!webhookUrl.startsWith("https://hooks.slack.com/")) {
                return StepResult.failed("Slack node: invalid webhook URL, must start with https://hooks.slack.com/");
            }

            String messageTemplate = nodeConfig.has("message") ? nodeConfig.get("message").asText() : null;
            String channel = nodeConfig.has("channel") ? nodeConfig.get("channel").asText() : null;

            // Build message text
            String text;
            if (messageTemplate != null && !messageTemplate.isBlank()) {
                text = resolvePlaceholders(messageTemplate, input);
            } else {
                text = input != null ? input.toString() : "Workflow notification";
            }

            // Build Slack payload
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("text", text);
            if (channel != null && !channel.isBlank()) {
                payload.put("channel", channel);
            }

            String response = webClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("User-Agent", "RailhookWorkflow/1.0")
                    .bodyValue(payload.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            ObjectNode output = objectMapper.createObjectNode();
            output.put("sent", true);
            output.put("response", response);
            output.put("message", text);
            return StepResult.success(output);
        } catch (Exception e) {
            log.error("Slack node execution failed: {}", e.getMessage(), e);
            return StepResult.failed("Slack error: " + e.getMessage());
        }
    }

    private String resolvePlaceholders(String template, JsonNode input) {
        if (input == null) return template;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            JsonNode value = resolvePath(path, input);
            String replacement = (value != null && !value.isMissingNode() && !value.isNull())
                    ? (value.isTextual() ? value.textValue() : value.toString())
                    : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private JsonNode resolvePath(String path, JsonNode root) {
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String segment : segments) {
            if (current == null || current.isMissingNode() || current.isNull()) return null;
            current = current.path(segment);
        }
        return current;
    }
}
