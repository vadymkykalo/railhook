package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Interface for all workflow node executors.
 * Each implementation handles one node type (filter, transform, http, slack, etc.)
 */
public interface NodeExecutor {

    /** Node type identifier — must match the React Flow node type. */
    String getType();

    /** Execute this node with given config and input data. */
    StepResult execute(JsonNode nodeConfig, JsonNode input);
}
