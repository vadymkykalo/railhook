package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BranchNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private BranchNodeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BranchNodeExecutor(mapper);
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void getType_returnsBranch() {
        assertThat(executor.getType()).isEqualTo("branch");
    }

    @Test
    void noConditions_alwaysTakesTrueBranch() throws Exception {
        StepResult result = executor.execute(json("{}"), json("{\"amount\":10}"));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("_branchHandle").asText()).isEqualTo("true");
        assertThat(result.output().get("_branchResult").asBoolean()).isTrue();
    }

    @Test
    void nullConditions_alwaysTakesTrueBranch() throws Exception {
        StepResult result = executor.execute(json("{\"conditions\":null}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("_branchHandle").asText()).isEqualTo("true");
    }

    @Test
    void matchingCondition_routesToTrueHandle() throws Exception {
        JsonNode config = json("""
                {
                  "conditions": {
                    "type": "predicate",
                    "field": "amount",
                    "operator": "GT",
                    "value": 100,
                    "valueType": "NUMBER"
                  }
                }
                """);
        StepResult result = executor.execute(config, json("{\"amount\":200}"));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("_branchHandle").asText()).isEqualTo("true");
        assertThat(result.output().get("_branchResult").asBoolean()).isTrue();
    }

    @Test
    void nonMatchingCondition_routesToFalseHandle() throws Exception {
        JsonNode config = json("""
                {
                  "conditions": {
                    "type": "predicate",
                    "field": "amount",
                    "operator": "GT",
                    "value": 100,
                    "valueType": "NUMBER"
                  }
                }
                """);
        StepResult result = executor.execute(config, json("{\"amount\":10}"));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("_branchHandle").asText()).isEqualTo("false");
        assertThat(result.output().get("_branchResult").asBoolean()).isFalse();
    }

    @Test
    void output_preservesOriginalInputFields() throws Exception {
        JsonNode config = json("{}");
        JsonNode input = json("{\"orderId\":\"o-1\",\"amount\":50}");

        StepResult result = executor.execute(config, input);

        assertThat(result.output().get("orderId").asText()).isEqualTo("o-1");
        assertThat(result.output().get("amount").asInt()).isEqualTo(50);
    }

    @Test
    void nonObjectInput_doesNotThrow() throws Exception {
        StepResult result = executor.execute(json("{}"), json("[1,2,3]"));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("_branchHandle").asText()).isEqualTo("true");
    }

    @Test
    void nullInput_doesNotThrow() {
        StepResult result = executor.execute(mapper.createObjectNode(), null);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("_branchHandle").asText()).isEqualTo("true");
    }

    @Test
    void malformedConditions_returnsFailed() throws Exception {
        JsonNode config = json("{\"conditions\": {\"type\": \"not-a-real-type\"}}");

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("Branch error");
    }
}
