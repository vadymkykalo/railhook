package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class SlackNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SlackNodeExecutor executor = new SlackNodeExecutor(WebClient.builder(), mapper);

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void missingWebhookUrl_returnsFailed() throws Exception {
        StepResult result = executor.execute(json("{}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("webhookUrl is required");
    }

    @Test
    void nonSlackHost_isRejected() throws Exception {
        JsonNode config = json("{\"webhookUrl\":\"https://evil.example.com/webhook\"}");

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("must start with https://hooks.slack.com/");
    }

    @Test
    void httpSlackUrl_isRejected() throws Exception {
        JsonNode config = json("{\"webhookUrl\":\"http://hooks.slack.com/services/x\"}");

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("must start with https://hooks.slack.com/");
    }
}
