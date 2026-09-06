package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookTriggerExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebhookTriggerExecutor executor = new WebhookTriggerExecutor();

    @Test
    void getType_returnsWebhookTrigger() {
        assertThat(executor.getType()).isEqualTo("webhookTrigger");
    }

    @Test
    void execute_passesThroughInputUnchanged() throws Exception {
        JsonNode input = mapper.readTree("{\"orderId\":\"o-1\",\"amount\":42}");

        StepResult result = executor.execute(mapper.createObjectNode(), input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(input);
    }

    @Test
    void execute_nullInput_passesThroughNull() {
        StepResult result = executor.execute(mapper.createObjectNode(), null);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output()).isNull();
    }

    @Test
    void execute_ignoresNodeConfig() throws Exception {
        JsonNode input = mapper.readTree("{\"a\":1}");
        JsonNode config = mapper.readTree("{\"whatever\":\"config\"}");

        StepResult result = executor.execute(config, input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(input);
    }
}
