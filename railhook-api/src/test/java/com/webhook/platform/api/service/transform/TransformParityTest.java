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

/**
 * The api half of the parity check.
 *
 * <p>Its twin lives in {@code railhook-worker} under the same name and runs the same script,
 * from the same fixture, through the delivery path instead. Neither module depends on the
 * other, so the fixture — {@code transform/contract-example.json}, shipped in common — is what
 * holds them together: if either call site ever stops going through
 * {@link JavaScriptTransformEngine} and starts doing its own thing, one of the two goes red
 * against bytes the other still produces.
 *
 * <p>That is not hypothetical. The template language has exactly this shape and no such test,
 * and its two copies have disagreed about whether a bad JSONPath is an error for a year.
 */
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
