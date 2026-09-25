package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.service.transform.TemplateTransformer;
import com.webhook.platform.api.service.transform.TransformationRunner;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptLimits;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransformNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TransformationRepository transformationRepository;
    private TransformNodeExecutor executor;

    @BeforeEach
    void setUp() {
        transformationRepository = mock(TransformationRepository.class);
        TemplateTransformer templateTransformer = new TemplateTransformer(mapper);
        executor = new TransformNodeExecutor(mapper, transformationRepository, templateTransformer,
                new TransformationRunner(templateTransformer,
                        new JavaScriptTransformEngine(mapper, ScriptLimits.defaults()), mapper));
    }

    private Transformation saved(String template, boolean enabled) {
        return Transformation.builder()
                .id(UUID.randomUUID())
                .name("Flatten the customer")
                .template(template)
                .enabled(enabled)
                .build();
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void noTemplate_passesThroughInput() throws Exception {
        JsonNode input = json("{\"a\":1}");
        StepResult result = executor.execute(json("{}"), input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(input);
    }

    @Test
    void jsonTemplate_resolvesPlaceholdersFromInput() throws Exception {
        JsonNode config = json("""
                {
                  "template": "{\\"orderId\\": \\"{{data.id}}\\", \\"customer\\": \\"{{data.customer.name}}\\", \\"fixed\\": 42}"
                }
                """);
        JsonNode input = json("{\"data\":{\"id\":\"o-1\",\"customer\":{\"name\":\"Ada\"}}}");

        StepResult result = executor.execute(config, input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("orderId").asText()).isEqualTo("o-1");
        assertThat(result.output().get("customer").asText()).isEqualTo("Ada");
        assertThat(result.output().get("fixed").asInt()).isEqualTo(42);
    }

    @Test
    void plainStringTemplate_wrapsResolvedValueInResultField() throws Exception {
        JsonNode config = json("{\"template\":\"Order {{orderId}} received\"}");
        JsonNode input = json("{\"orderId\":\"o-9\"}");

        StepResult result = executor.execute(config, input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("result").asText()).isEqualTo("Order o-9 received");
    }

    @Test
    void missingPlaceholderPath_resolvesToEmptyString() throws Exception {
        JsonNode config = json("{\"template\":\"value={{missing.path}}\"}");
        JsonNode input = json("{}");

        StepResult result = executor.execute(config, input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("result").asText()).isEqualTo("value=");
    }

    @Test
    void malformedJsonTemplate_returnsFailed() throws Exception {
        JsonNode config = json("{\"template\":\"{not valid json\"}");

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("Transform error");
    }

    // A saved transformation is ${$.path} JSONPath, the inline template {{field}}: not interchangeable.
    @Test
    void savedTransformation_runsInItsOwnSyntax() throws Exception {
        UUID id = UUID.randomUUID();
        when(transformationRepository.findById(id))
                .thenReturn(Optional.of(saved("{\"email\":\"${$.customer.email}\",\"kind\":\"invoice\"}", true)));

        StepResult result = executor.execute(
                json("{\"transformationId\":\"" + id + "\"}"),
                json("{\"customer\":{\"email\":\"ada@example.com\"}}"));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().path("email").asText()).isEqualTo("ada@example.com");
        assertThat(result.output().path("kind").asText()).isEqualTo("invoice");
    }

    @Test
    void missingTransformation_failsRatherThanPassingThePayloadThrough() throws Exception {
        UUID id = UUID.randomUUID();
        when(transformationRepository.findById(id)).thenReturn(Optional.empty());

        StepResult result = executor.execute(json("{\"transformationId\":\"" + id + "\"}"), json("{\"a\":1}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains(id.toString());
    }

    @Test
    void disabledTransformation_failsToo() throws Exception {
        UUID id = UUID.randomUUID();
        when(transformationRepository.findById(id)).thenReturn(Optional.of(saved("{\"a\":1}", false)));

        StepResult result = executor.execute(json("{\"transformationId\":\"" + id + "\"}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).containsIgnoringCase("disabled");
    }

    @Test
    void reference_winsOverAnInlineTemplateLeftBehind() throws Exception {
        UUID id = UUID.randomUUID();
        when(transformationRepository.findById(id))
                .thenReturn(Optional.of(saved("{\"from\":\"saved\"}", true)));

        StepResult result = executor.execute(
                json("{\"transformationId\":\"" + id + "\",\"template\":\"{\\\"from\\\":\\\"inline\\\"}\"}"),
                json("{}"));

        assertThat(result.output().path("from").asText()).isEqualTo("saved");
    }

}
