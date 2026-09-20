package com.webhook.platform.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptLimits;
import com.webhook.platform.common.transform.TemplateLanguageConformance;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * This module's half of the template-language parity check.
 *
 * <p>The api runs the same corpus against {@code TemplateTransformer} in
 * {@code TemplateLanguageParityTest} of its own, because the two modules are siblings in the
 * reactor and neither is on the other's classpath. Same expected values, two implementations: a
 * case that only one of them gets right fails in exactly one module, which names the drift.
 *
 * <p>{@link TemplateLanguageConformance} says why there are two implementations and where they
 * are allowed to differ.
 */
@DisplayName("PayloadTransformService conforms to the shared template-language corpus")
class TemplateLanguageParityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayloadTransformService service = new PayloadTransformService(
            objectMapper, new SimpleMeterRegistry(),
            // The corpus is entirely template cases, so the engine is never touched — and it
            // builds itself lazily, so this costs nothing.
            new JavaScriptTransformEngine(objectMapper, ScriptLimits.defaults()));

    static List<TemplateLanguageConformance.Case> cases() {
        return TemplateLanguageConformance.cases();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void conforms(TemplateLanguageConformance.Case testCase) throws Exception {
        String actual = service.transform(testCase.payload(), testCase.template());

        assertThat(objectMapper.readTree(actual))
                .isEqualTo(objectMapper.readTree(testCase.expected()));
    }

    @Test
    @DisplayName("the one deliberate difference: a malformed path throws here, and is null in the api")
    void malformedPathThrowsHere() {
        // Not in the shared corpus, because the corpus is what both sides must agree on. This is
        // the agreed disagreement, and this side is the one that matters: a delivered, signed body
        // with nulls where the data should have been is the bug evaluateJsonPath's javadoc is
        // about. The api's preview renders null instead, because a template being typed is not yet
        // wrong.
        assertThatThrownBy(() -> service.transform("{\"a\":1}", "{\"x\":\"${$.[[nope}\"}"))
                .isInstanceOf(PayloadTransformException.class);
    }
}
