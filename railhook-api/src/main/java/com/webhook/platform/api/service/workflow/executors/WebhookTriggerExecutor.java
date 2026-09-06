package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import org.springframework.stereotype.Component;

/**
 * Webhook Trigger node — the entry point of a workflow.
 * Simply passes through the trigger event payload as output.
 */
@Component
public class WebhookTriggerExecutor implements NodeExecutor {

    @Override
    public String getType() {
        return "webhookTrigger";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        // Trigger node just passes through the event payload
        return StepResult.success(input);
    }
}
