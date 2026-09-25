package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import org.springframework.stereotype.Component;

@Component
public class WebhookTriggerExecutor implements NodeExecutor {

    @Override
    public String getType() {
        return "webhookTrigger";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        return StepResult.success(input);
    }
}
