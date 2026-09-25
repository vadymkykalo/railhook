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

// The api runs the same corpus against TemplateTransformer; neither module sees the other.
@DisplayName("PayloadTransformService conforms to the shared template-language corpus")
class TemplateLanguageParityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayloadTransformService service = new PayloadTransformService(
            objectMapper, new SimpleMeterRegistry(),
            // The corpus is all template cases, and the engine builds lazily, so this costs nothing.
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
        // The agreed disagreement: a signed body with nulls is the bug; the api's preview renders null.
        assertThatThrownBy(() -> service.transform("{\"a\":1}", "{\"x\":\"${$.[[nope}\"}"))
                .isInstanceOf(PayloadTransformException.class);
    }
}
