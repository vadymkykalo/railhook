package com.webhook.platform.api.service.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.transform.TemplateLanguageConformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// The worker runs the same corpus against PayloadTransformService; neither module sees the other.
@DisplayName("TemplateTransformer conforms to the shared template-language corpus")
class TemplateLanguageParityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateTransformer transformer = new TemplateTransformer(objectMapper);

    static List<TemplateLanguageConformance.Case> cases() {
        return TemplateLanguageConformance.cases();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void conforms(TemplateLanguageConformance.Case testCase) throws Exception {
        JsonNode source = objectMapper.readTree(testCase.payload());

        JsonNode actual = transformer.apply(testCase.template(), source);

        assertThat(actual).isEqualTo(objectMapper.readTree(testCase.expected()));
    }

    @Test
    @DisplayName("the one deliberate difference: a malformed path is null here, and throws in the worker")
    void malformedPathIsNullHere() throws Exception {
        // The agreed disagreement: a preview renders a half-typed template, a delivery must not.
        JsonNode result = transformer.apply("{\"x\":\"${$.[[nope}\"}", objectMapper.readTree("{\"a\":1}"));

        assertThat(result.get("x").isNull()).isTrue();
    }
}
