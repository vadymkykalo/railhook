package com.webhook.platform.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A *configured* payload transformation that fails to apply must never fall back to
 * shipping the raw payload — customers use transformations to strip PII before a payload
 * leaves the platform, so a broken template must fail loudly (retryable), not silently.
 *
 * "No transformation configured" (null/blank template) is a distinct, non-error case and
 * must keep returning the original payload unchanged.
 */
class PayloadTransformServiceTest {

    private SimpleMeterRegistry meterRegistry;
    private PayloadTransformService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new PayloadTransformService(new ObjectMapper(), meterRegistry);
    }

    @Test
    void transform_noTemplateConfigured_returnsOriginalPayload_nullTemplate() {
        String original = "{\"id\":\"evt_1\",\"pii\":\"ssn-123-45-6789\"}";

        String result = service.transform(original, null);

        assertEquals(original, result, "no transformation configured must send the payload as-is");
    }

    @Test
    void transform_noTemplateConfigured_returnsOriginalPayload_blankTemplate() {
        String original = "{\"id\":\"evt_1\",\"pii\":\"ssn-123-45-6789\"}";

        String result = service.transform(original, "   ");

        assertEquals(original, result, "a blank template means no transformation configured");
    }

    @Test
    void transform_validTemplate_appliesJsonPathSubstitution() {
        String original = "{\"id\":\"evt_1\",\"pii\":\"secret\",\"data\":{\"customer\":{\"name\":\"Ada\"}}}";
        String template = "{\"event_id\":\"${$.id}\",\"customer_name\":\"${$.data.customer.name}\"}";

        String result = service.transform(original, template);

        assertEquals("{\"event_id\":\"evt_1\",\"customer_name\":\"Ada\"}", result);
    }

    /**
     * Reproduces the original bug: a broken (non-JSON) template used to be swallowed
     * and the raw, untransformed payload — including the PII the transform exists to strip —
     * was returned instead. On fixed code this must throw rather than leaking the payload.
     */
    @Test
    void transform_brokenTemplateSyntax_throwsInsteadOfLeakingRawPayload() {
        String original = "{\"id\":\"evt_1\",\"pii\":\"ssn-123-45-6789\"}";
        String brokenTemplate = "{ this is not valid json ";

        PayloadTransformException ex = assertThrows(PayloadTransformException.class,
                () -> service.transform(original, brokenTemplate));

        assertEquals(1, meterRegistry.get("transform_failed_total").counter().count(),
                "a configured-but-failing transform must be counted, not just warn-logged");
        org.junit.jupiter.api.Assertions.assertNotNull(ex.getMessage());
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
    void transform_doesNotIncrementFailureCounter_whenNoTemplateConfigured() {
        service.transform("{\"id\":1}", null);

        assertEquals(0.0, meterRegistry.get("transform_failed_total").counter().count(),
                "no transformation configured is not a failure and must not be counted");
    }

    @Test
    void transform_malformedJsonPathInsideTemplate_throwsRatherThanEmittingNulls() {
        /* The template parses as JSON and the source parses as JSON, so neither of the two
           existing guards fires. What is broken is one JSONPath *inside* it — a typo an author
           makes and a validator does not catch. evaluateJsonPath used to swallow that at DEBUG
           and substitute a JSON null, so the receiver got a successfully-delivered,
           successfully-signed body with nulls where the data should have been, and nothing
           anywhere said so.

           That contradicted two written contracts at once: AttemptStore.buildBody ("never
           return the untransformed payload" — the transformation is meant to be all-or-nothing)
           and AttemptRunner's invariant 4. It also defeats the case the javadoc on transform()
           names: a template whose job is to strip PII by whitelisting fields is exactly the
           template whose silent misfire nobody notices. */
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
        /* The other half, and the reason the fix is not "throw on anything unusual": a path
           that is well-formed but matches nothing is an ordinary optional field. Failing the
           delivery for an absent field would make every optional key in a template a
           liability. */
        String payload = "{\"id\":\"evt_1\"}";
        String template = "{\"id\":\"${$.id}\",\"maybe\":\"${$.nope}\"}";

        String result = service.transform(payload, template);

        assertTrue(result.contains("evt_1"), "the field that does exist still comes through");
        assertTrue(result.contains("\"maybe\""), "the optional field is present, just empty");
    }
}
