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

/**
 * This module's half of the template-language parity check.
 *
 * <p>The worker runs the same corpus against {@code PayloadTransformService} in
 * {@code TemplateLanguageParityTest} of its own, because the two modules are siblings in the
 * reactor and neither is on the other's classpath. Same expected values, two implementations: a
 * case that only one of them gets right fails in exactly one module, which names the drift.
 *
 * <p>{@link TemplateLanguageConformance} says why there are two implementations and where they
 * are allowed to differ.
 */
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
        // Not in the shared corpus, because the corpus is what both sides must agree on. This is
        // the agreed disagreement: a preview has to render something for a template someone is
        // still typing, while a delivery must not ship a body with a hole in it. Saving a
        // transformation compiles every path (TransformationService.validateTemplate), so a stored
        // template cannot reach the worker in this state in the first place.
        JsonNode result = transformer.apply("{\"x\":\"${$.[[nope}\"}", objectMapper.readTree("{\"a\":1}"));

        assertThat(result.get("x").isNull()).isTrue();
    }
}
