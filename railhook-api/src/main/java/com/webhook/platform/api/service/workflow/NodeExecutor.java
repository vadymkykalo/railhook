package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;

public interface NodeExecutor {

    // Must match the node type the UI canvas saves.
    String getType();

    StepResult execute(JsonNode nodeConfig, JsonNode input);
}
