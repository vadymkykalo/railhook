package com.webhook.platform.api.service.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptLimits;
import com.webhook.platform.common.transform.TransformContractExample;
import com.webhook.platform.common.transform.TransformationKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Its worker twin runs the same fixture through the delivery path; neither module depends on the other.
class TransformParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JavaScriptTransformEngine engine;
    private static TransformationRunner runner;
    private static TransformContractExample example;

    @BeforeAll
    static void setUp() {
        engine = new JavaScriptTransformEngine(MAPPER, ScriptLimits.defaults());
        runner = new TransformationRunner(new TemplateTransformer(MAPPER), engine, MAPPER);
        example = TransformContractExample.load(MAPPER);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void thePreviewPathProducesTheContractExampleExactly() {
        TransformationRunner.Result result =
                runner.run(TransformationKind.JAVASCRIPT, example.script(), example.request());

        assertThat(result.cancelled()).isFalse();
        assertThat(result.payload().toString()).isEqualTo(example.expectedPayloadJson());
        assertThat(result.headers()).isEqualTo(example.expectedHeaders());
    }
}
