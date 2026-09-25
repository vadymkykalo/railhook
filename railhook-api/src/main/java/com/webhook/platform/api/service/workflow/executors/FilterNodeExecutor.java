package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.ConditionNode;
import com.webhook.platform.api.service.rules.ConditionTreeEvaluator;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Passes input through when the conditions match, and skips otherwise. */
@Component
@Slf4j
@RequiredArgsConstructor
public class FilterNodeExecutor implements NodeExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "filter";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        try {
            JsonNode conditionsNode = nodeConfig.get("conditions");
            if (conditionsNode == null || conditionsNode.isNull() || conditionsNode.isMissingNode()) {
                return StepResult.success(input); // no conditions = pass through
            }

            ConditionNode conditions = objectMapper.treeToValue(conditionsNode, ConditionNode.class);
            Map<String, JsonNode> fieldCache = ConditionTreeEvaluator.newFieldCache();
            boolean matched = ConditionTreeEvaluator.evaluate(conditions, input, fieldCache);

            if (matched) {
                return StepResult.success(input);
            } else {
                return StepResult.skipped("Filter conditions not matched");
            }
        } catch (Exception e) {
            log.error("Filter node execution failed: {}", e.getMessage(), e);
            return StepResult.failed("Filter error: " + e.getMessage());
        }
    }
}
