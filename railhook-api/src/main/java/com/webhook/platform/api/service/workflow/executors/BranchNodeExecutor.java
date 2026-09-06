package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.dto.ConditionNode;
import com.webhook.platform.api.service.rules.ConditionTreeEvaluator;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Branch node — evaluates conditions and outputs which handle to follow.
 * Config: conditions (ConditionNode tree).
 * If conditions match → output includes _branchHandle = "true"
 * If not matched → output includes _branchHandle = "false"
 * The WorkflowEngine uses _branchHandle to route to the correct downstream edge.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BranchNodeExecutor implements NodeExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "branch";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        try {
            JsonNode conditionsNode = nodeConfig.get("conditions");
            boolean matched;

            if (conditionsNode == null || conditionsNode.isNull() || conditionsNode.isMissingNode()) {
                matched = true; // no conditions = always true branch
            } else {
                ConditionNode conditions = objectMapper.treeToValue(conditionsNode, ConditionNode.class);
                Map<String, JsonNode> fieldCache = ConditionTreeEvaluator.newFieldCache();
                matched = ConditionTreeEvaluator.evaluate(conditions, input, fieldCache);
            }

            ObjectNode output = objectMapper.createObjectNode();
            // Copy input data to output
            if (input != null && input.isObject()) {
                output.setAll((ObjectNode) input.deepCopy());
            }
            // Add branch decision metadata
            output.put("_branchHandle", matched ? "true" : "false");
            output.put("_branchResult", matched);

            return StepResult.success(output);
        } catch (Exception e) {
            log.error("Branch node execution failed: {}", e.getMessage(), e);
            return StepResult.failed("Branch error: " + e.getMessage());
        }
    }
}
