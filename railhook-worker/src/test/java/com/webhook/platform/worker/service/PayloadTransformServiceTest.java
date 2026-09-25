package com.webhook.platform.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptLimits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A configured transformation that fails must never fall back to the raw, possibly PII-bearing payload. */
class PayloadTransformServiceTest {

    private SimpleMeterRegistry meterRegistry;
    private PayloadTransformService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new PayloadTransformService(new ObjectMapper(), meterRegistry, scriptEngine());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void transform_noTemplateConfigured_returnsOriginalPayloadAndCountsNoFailure(String template) {
        String original = "{\"id\":\"evt_1\",\"pii\":\"ssn-123-45-6789\"}";

        assertEquals(original, service.transform(original, template));
        assertEquals(0.0, meterRegistry.get("transform_failed_total").counter().count());
    }

    @Test
    void transform_validTemplate_appliesJsonPathSubstitution() {
        String original = "{\"id\":\"evt_1\",\"pii\":\"secret\",\"data\":{\"customer\":{\"name\":\"Ada\"}}}";
        String template = "{\"event_id\":\"${$.id}\",\"customer_name\":\"${$.data.customer.name}\"}";

        String result = service.transform(original, template);

        assertEquals("{\"event_id\":\"evt_1\",\"customer_name\":\"Ada\"}", result);
    }

    @Test
    void transform_brokenTemplateSyntax_throwsInsteadOfLeakingRawPayload() {
        String original = "{\"id\":\"evt_1\",\"pii\":\"ssn-123-45-6789\"}";
        String brokenTemplate = "{ this is not valid json ";

        PayloadTransformException ex = assertThrows(PayloadTransformException.class,
                () -> service.transform(original, brokenTemplate));

        assertEquals(1, meterRegistry.get("transform_failed_total").counter().count(),
                "a configured-but-failing transform must be counted, not just warn-logged");
        assertNotNull(ex.getMessage());
    }

    @Test
    void transform_sourcePayloadIsInvalidJson_throwsInsteadOfLeakingRawPayload() {
        String invalidSourcePayload = "not-json-at-all";
        String template = "{\"event_id\":\"${$.id}\"}";

        assertThrows(PayloadTransformException.class,
                () -> service.transform(invalidSourcePayload, template));

        assertEquals(1, meterRegistry.get("transform_failed_total").counter().count());
    }

    @Test
    void transform_malformedJsonPathInsideTemplate_throwsRatherThanEmittingNulls() {
        // A malformed path used to be swallowed and delivered as a signed body full of nulls.
        String payload = "{\"id\":\"evt_1\",\"email\":\"ada@example.com\"}";
        String template = "{\"id\":\"${$.[[not a path}\"}";

        PayloadTransformException ex = assertThrows(PayloadTransformException.class,
                () -> service.transform(payload, template));

        assertTrue(ex.getMessage().toLowerCase().contains("transformation failed"),
                "the failure has to name itself as a transformation failure: " + ex.getMessage());
        assertEquals(1.0, meterRegistry.get("transform_failed_total").counter().count(),
                "a silent miss is the bug; the counter is how an operator sees it");
    }

    @Test
    void transform_pathThatMatchesNothing_stillYieldsNullRatherThanFailing() {
        String payload = "{\"id\":\"evt_1\"}";
        String template = "{\"id\":\"${$.id}\",\"maybe\":\"${$.nope}\"}";

        String result = service.transform(payload, template);

        assertTrue(result.contains("evt_1"), "the field that does exist still comes through");
        assertTrue(result.contains("\"maybe\""), "the optional field is present, just empty");
    }

    private static JavaScriptTransformEngine scriptEngine() {
        return new JavaScriptTransformEngine(new ObjectMapper(), ScriptLimits.defaults());
    }
}
