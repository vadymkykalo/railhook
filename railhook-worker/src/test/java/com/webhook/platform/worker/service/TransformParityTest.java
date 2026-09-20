package com.webhook.platform.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptLimits;
import com.webhook.platform.common.transform.TransformContractExample;
import com.webhook.platform.common.transform.TransformationKind;
import com.webhook.platform.worker.attempt.TransformedBody;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The worker half of the parity check.
 *
 * <p>Its twin lives in {@code railhook-api} under the same name and runs the same script, from
 * the same fixture — {@code transform/contract-example.json}, shipped in common — through the
 * preview path instead. Neither module depends on the other, so the fixture is what holds the
 * two call sites together: a delivery that stopped producing the bytes the preview shows would
 * turn this red while the other stayed green, which is the whole point.
 */
class TransformParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JavaScriptTransformEngine engine;
    private static PayloadTransformService service;
    private static TransformContractExample example;

    @BeforeAll
    static void setUp() {
        engine = new JavaScriptTransformEngine(MAPPER, ScriptLimits.defaults());
        service = new PayloadTransformService(MAPPER, new SimpleMeterRegistry(), engine);
        example = TransformContractExample.load(MAPPER);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void theDeliveryPathProducesTheContractExampleExactly() {
        TransformedBody body = service.apply(
                new TransformationCacheService.Resolved(TransformationKind.JAVASCRIPT, example.script()),
                example.inputJson(),
                example.request());

        assertThat(body.cancelled()).isFalse();
        assertThat(body.body()).isEqualTo(example.expectedPayloadJson());
        assertThat(body.headers()).isEqualTo(example.expectedHeaders());
    }

    @Test
    void aScriptThatCancelsComesBackAsACancellation() {
        TransformedBody body = service.apply(
                new TransformationCacheService.Resolved(TransformationKind.JAVASCRIPT,
                        "function handler(w) { return { cancel: true, cancelReason: 'test traffic' }; }"),
                example.inputJson(),
                example.request());

        assertThat(body.cancelled()).isTrue();
        assertThat(body.cancelReason()).isEqualTo("test traffic");
        assertThat(body.body()).isNull();
    }

    /**
     * Invariant 4, for the new language: whatever goes wrong inside a script, the raw payload
     * does not go out in its place.
     */
    @Test
    void aFailingScriptIsARetryableTransformFailureNotAFallback() {
        assertThatThrownBy(() -> service.apply(
                new TransformationCacheService.Resolved(TransformationKind.JAVASCRIPT,
                        "function handler(w) { throw new Error('nope'); }"),
                example.inputJson(),
                example.request()))
                .isInstanceOf(PayloadTransformException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void anUnconfiguredTransformationLeavesTheBodyAlone() {
        assertThat(service.apply(null, "{\"a\":1}", example.request()).body()).isEqualTo("{\"a\":1}");
        assertThat(service.apply(
                TransformationCacheService.Resolved.template(null), "{\"a\":1}", example.request()).body())
                .isEqualTo("{\"a\":1}");
    }
}
