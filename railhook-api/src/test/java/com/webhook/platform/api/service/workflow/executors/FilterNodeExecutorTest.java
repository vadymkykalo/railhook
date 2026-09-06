package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private FilterNodeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new FilterNodeExecutor(mapper);
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void getType_returnsFilter() {
        assertThat(executor.getType()).isEqualTo("filter");
    }

    @Test
    void noConditions_passesThroughUnchanged() throws Exception {
        JsonNode input = json("{\"amount\":10}");
        StepResult result = executor.execute(json("{}"), input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(input);
    }

    @Test
    void matchingCondition_passesThrough() throws Exception {
        JsonNode config = json("""
                {
                  "conditions": {
                    "type": "predicate",
                    "field": "status",
                    "operator": "EQ",
                    "value": "active",
                    "valueType": "STRING"
                  }
                }
                """);
        JsonNode input = json("{\"status\":\"active\"}");

        StepResult result = executor.execute(config, input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(input);
    }

    @Test
    void nonMatchingCondition_isSkipped() throws Exception {
        JsonNode config = json("""
                {
                  "conditions": {
                    "type": "predicate",
                    "field": "status",
                    "operator": "EQ",
                    "value": "active",
                    "valueType": "STRING"
                  }
                }
                """);
        JsonNode input = json("{\"status\":\"inactive\"}");

        StepResult result = executor.execute(config, input);

        assertThat(result.status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.errorMessage()).isEqualTo("Filter conditions not matched");
        assertThat(result.output()).isNull();
    }

    @Test
    void malformedConditions_returnsFailed() throws Exception {
        JsonNode config = json("{\"conditions\": {\"type\": \"bogus\"}}");

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("Filter error");
    }

    @Test
    void nullConditionsNode_passesThrough() throws Exception {
        JsonNode input = json("{\"a\":1}");
        StepResult result = executor.execute(json("{\"conditions\":null}"), input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(input);
    }
}
