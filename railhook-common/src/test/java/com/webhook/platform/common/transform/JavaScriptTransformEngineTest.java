package com.webhook.platform.common.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class JavaScriptTransformEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Short so every escape test fails fast; production limits are an order of magnitude larger.
    private static final ScriptLimits LIMITS = new ScriptLimits(
            Duration.ofMillis(600), 48L * 1024 * 1024, 256 * 1024, 50, 64 * 1024);

    private static JavaScriptTransformEngine engine;

    @BeforeAll
    static void startEngine() {
        engine = new JavaScriptTransformEngine(MAPPER, LIMITS);
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    private static TransformRequest request(String payloadJson) {
        return TransformRequest.builder()
                .payload(payloadJson)
                .eventType("order.completed")
                .eventId("evt_1")
                .timestamp(Instant.parse("2026-09-20T10:00:00Z"))
                .direction("OUTGOING")
                .url("https://example.test/hook")
                .headers(Map.of("X-Existing", "1"))
                .attemptNumber(2)
                .build();
    }

    private static TransformOutcome run(String script, String payloadJson) {
        return engine.run(script, request(payloadJson));
    }

    @Nested
    @DisplayName("the handler contract")
    class Contract {

        @Test
        void returnsTheTransformedPayload() {
            TransformOutcome out = run("""
                    function handler(webhook) {
                      return { payload: { id: webhook.payload.id, doubled: webhook.payload.n * 2 } };
                    }
                    """, "{\"id\":\"a\",\"n\":21}");

            assertThat(out.cancelled()).isFalse();
            assertThat(out.payload()).isEqualTo("{\"id\":\"a\",\"doubled\":42}");
        }

        @Test
        void seesTheEventAndTheDeliveryContext() {
            TransformOutcome out = run("""
                    function handler(webhook) {
                      return { payload: {
                        type: webhook.eventType,
                        id: webhook.eventId,
                        at: webhook.timestamp,
                        dir: webhook.direction,
                        url: webhook.url,
                        hdr: webhook.headers['X-Existing'],
                        attempt: webhook.attemptNumber
                      } };
                    }
                    """, "{}");

            assertThat(out.payload())
                    .contains("\"type\":\"order.completed\"")
                    .contains("\"id\":\"evt_1\"")
                    .contains("\"at\":\"2026-09-20T10:00:00Z\"")
                    .contains("\"dir\":\"OUTGOING\"")
                    .contains("\"url\":\"https://example.test/hook\"")
                    .contains("\"hdr\":\"1\"")
                    .contains("\"attempt\":2");
        }

        @Test
        void mayAddHeaders() {
            TransformOutcome out = run("""
                    function handler(webhook) {
                      return { payload: webhook.payload, headers: { 'X-Tenant': 'acme', 'X-N': 7 } };
                    }
                    """, "{\"a\":1}");

            assertThat(out.headers()).containsEntry("X-Tenant", "acme").containsEntry("X-N", "7");
        }

        @Test
        void mayCancelTheDelivery() {
            TransformOutcome out = run("""
                    function handler(webhook) {
                      if (webhook.payload.test) return { cancel: true, cancelReason: 'test traffic' };
                      return { payload: webhook.payload };
                    }
                    """, "{\"test\":true}");

            assertThat(out.cancelled()).isTrue();
            assertThat(out.cancelReason()).isEqualTo("test traffic");
            assertThat(out.payload()).isNull();
        }

        @Test
        void arrowFunctionAssignedToHandlerAlsoWorks() {
            TransformOutcome out = run(
                    "const handler = (webhook) => ({ payload: { ok: true } });", "{}");
            assertThat(out.payload()).isEqualTo("{\"ok\":true}");
        }

        @Test
        void refusesAScriptWithNoHandler() {
            assertThatThrownBy(() -> run("var x = 1;", "{}"))
                    .isInstanceOf(ScriptTransformException.class)
                    .extracting(e -> ((ScriptTransformException) e).reason())
                    .isEqualTo(ScriptTransformException.Reason.CONTRACT);
        }

        @Test
        void refusesAReturnWithNeitherPayloadNorCancel() {
            ScriptTransformException e = catchThrowableOfType(
                    () -> run("function handler(w) { return { nope: 1 }; }", "{}"),
                    ScriptTransformException.class);
            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.CONTRACT);
            assertThat(e.getMessage()).contains("payload");
        }

        @Test
        void aThrowingScriptIsARuntimeFailureCarryingItsOwnLineNumber() {
            ScriptTransformException e = catchThrowableOfType(() -> run("""
                    function handler(webhook) {
                      var x = 1;
                      throw new Error('boom');
                    }
                    """, "{}"), ScriptTransformException.class);

            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.RUNTIME);
            assertThat(e.getMessage()).contains("boom");
            assertThat(e.line()).isEqualTo(3);
        }

        @Test
        void aSyntaxErrorIsReportedAgainstTheAuthorsOwnLine() {
            ScriptTransformException e = catchThrowableOfType(
                    () -> run("function handler(w) {\n  return {{;\n}", "{}"),
                    ScriptTransformException.class);

            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.SYNTAX);
            assertThat(e.line()).isEqualTo(2);
        }

        @Test
        void validateAcceptsACompilableScriptAndRejectsABrokenOrHandlerlessOne() {
            engine.validate("function handler(w) { return { payload: w.payload }; }");
            assertThatThrownBy(() -> engine.validate("function handler(w) { return {{; }"))
                    .isInstanceOf(ScriptTransformException.class);
            assertThatThrownBy(() -> engine.validate("var x = 1;"))
                    .isInstanceOf(ScriptTransformException.class)
                    .extracting(e -> ((ScriptTransformException) e).reason())
                    .isEqualTo(ScriptTransformException.Reason.CONTRACT);
        }
    }

    @Nested
    @DisplayName("console")
    class Console {

        @Test
        void isCapturedAndCarriesItsLevel() {
            TransformOutcome out = run("""
                    function handler(webhook) {
                      console.log('hello', 42);
                      console.warn({ a: 1 });
                      console.error('bad');
                      return { payload: {} };
                    }
                    """, "{}");

            assertThat(out.console()).extracting(ScriptConsoleLine::level)
                    .containsExactly("log", "warn", "error");
            assertThat(out.console().get(0).message()).isEqualTo("hello 42");
            assertThat(out.console().get(1).message()).isEqualTo("{\"a\":1}");
        }

        @Test
        void survivesAScriptThatThrowsAfterLogging() {
            ScriptTransformException e = catchThrowableOfType(() -> run("""
                    function handler(webhook) {
                      console.log('got here');
                      throw new Error('then died');
                    }
                    """, "{}"), ScriptTransformException.class);

            assertThat(e.console()).extracting(ScriptConsoleLine::message).containsExactly("got here");
        }

        @Test
        void isCappedSoAChattyScriptCannotFillMemoryOrAResponse() {
            TransformOutcome out = run("""
                    function handler(webhook) {
                      for (var i = 0; i < 5000; i++) console.log('line ' + i);
                      return { payload: {} };
                    }
                    """, "{}");

            assertThat(out.console()).hasSize(LIMITS.maxConsoleLines());
            assertThat(out.consoleTruncated()).isTrue();
        }
    }

    @Nested
    @DisplayName("the sandbox refuses")
    class Sandbox {

        @Test
        void anInfiniteLoop() {
            ScriptTransformException e = catchThrowableOfType(
                    () -> run("function handler(w) { while (true) {} }", "{}"),
                    ScriptTransformException.class);
            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.TIMEOUT);
        }

        // A guest catch/finally must not be able to swallow the cancellation and carry on.
        @Test
        void anInfiniteLoopWrappedInTryCatch() {
            ScriptTransformException e = catchThrowableOfType(() -> run("""
                    function handler(webhook) {
                      try { while (true) {} } catch (err) { return { payload: { escaped: true } }; }
                      finally { while (true) {} }
                    }
                    """, "{}"), ScriptTransformException.class);
            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.TIMEOUT);
        }

        @Test
        void aHugeAllocation() {
            ScriptTransformException e = catchThrowableOfType(() -> run("""
                    function handler(webhook) {
                      var a = [];
                      while (true) { a.push(new Array(200000).fill('xxxxxxxxxxxxxxxx')); }
                    }
                    """, "{}"), ScriptTransformException.class);

            assertThat(e.reason())
                    .isIn(ScriptTransformException.Reason.MEMORY, ScriptTransformException.Reason.TIMEOUT);
        }

        @Test
        void reachingForAJavaClass() {
            ScriptTransformException e = catchThrowableOfType(
                    () -> run("function handler(w) { return { payload: Java.type('java.lang.System').getenv() }; }", "{}"),
                    ScriptTransformException.class);
            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.RUNTIME);
            assertThat(e.getMessage()).containsIgnoringCase("java");
        }

        @Test
        void theJavaPackageGlobals() {
            ScriptTransformException e = catchThrowableOfType(
                    () -> run("function handler(w) { return { payload: { f: new java.io.File('/etc/passwd').length() } }; }", "{}"),
                    ScriptTransformException.class);
            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.RUNTIME);
        }

        @Test
        void openingASocketOrReadingAFile() {
            for (String attempt : List.of(
                    "new java.net.Socket('example.com', 80)",
                    "fetch('https://example.com')",
                    "new XMLHttpRequest()",
                    "require('fs').readFileSync('/etc/passwd')",
                    "load('https://example.com/evil.js')",
                    "readFully('/etc/passwd')")) {
                ScriptTransformException e = catchThrowableOfType(
                        () -> run("function handler(w) { return { payload: " + attempt + " }; }", "{}"),
                        ScriptTransformException.class);
                assertThat(e)
                        .describedAs("attempt %s must be refused", attempt)
                        .isNotNull();
                assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.RUNTIME);
            }
        }

        @Test
        void startingAThread() {
            ScriptTransformException e = catchThrowableOfType(
                    () -> run("function handler(w) { new Worker('x'); return { payload: {} }; }", "{}"),
                    ScriptTransformException.class);
            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.RUNTIME);
        }

        @Test
        void readingTheHostEnvironment() {
            for (String attempt : List.of("process.env", "Polyglot.eval('js','1')", "Packages.java.lang.System")) {
                assertThatThrownBy(() -> run("function handler(w) { return { payload: " + attempt + " }; }", "{}"))
                        .describedAs("attempt %s must be refused", attempt)
                        .isInstanceOf(ScriptTransformException.class);
            }
        }

        @Test
        void anOutputLargerThanTheCeiling() {
            ScriptTransformException e = catchThrowableOfType(() -> run("""
                    function handler(webhook) {
                      return { payload: { big: 'x'.repeat(400000) } };
                    }
                    """, "{}"), ScriptTransformException.class);
            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.OUTPUT_TOO_LARGE);
        }

        @Test
        void aScriptLongerThanTheSourceCeiling() {
            String tooLong = "function handler(w) { /*" + "x".repeat(LIMITS.maxSourceChars()) + "*/ }";
            ScriptTransformException e = catchThrowableOfType(() -> run(tooLong, "{}"),
                    ScriptTransformException.class);
            assertThat(e.reason()).isEqualTo(ScriptTransformException.Reason.SOURCE_TOO_LARGE);
        }
    }

    @Nested
    @DisplayName("after a script is killed")
    class Recovery {

        @Test
        void theNextScriptStillRuns() {
            assertThatThrownBy(() -> run("function handler(w) { while (true) {} }", "{}"))
                    .isInstanceOf(ScriptTransformException.class);

            TransformOutcome out = run("function handler(w) { return { payload: { ok: 1 } }; }", "{}");
            assertThat(out.payload()).isEqualTo("{\"ok\":1}");
        }

        @Test
        void oneTenantsRunawayScriptDoesNotStopAnother() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                Future<?> runaway = pool.submit(() ->
                        assertThatThrownBy(() -> run("function handler(w) { while (true) {} }", "{}"))
                                .isInstanceOf(ScriptTransformException.class));

                List<Future<TransformOutcome>> others = IntStream.range(0, 3)
                        .mapToObj(i -> pool.submit(() ->
                                run("function handler(w) { return { payload: { i: " + i + " } }; }", "{}")))
                        .toList();

                for (Future<TransformOutcome> f : others) {
                    assertThat(f.get(10, TimeUnit.SECONDS).payload()).contains("\"i\":");
                }
                runaway.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void globalStateDoesNotLeakBetweenInvocations() {
            String script = """
                    if (typeof globalThis.__seen === 'undefined') globalThis.__seen = 0;
                    globalThis.__seen++;
                    function handler(webhook) { return { payload: { seen: globalThis.__seen } }; }
                    """;
            assertThat(run(script, "{}").payload()).isEqualTo("{\"seen\":1}");
            assertThat(run(script, "{}").payload()).isEqualTo("{\"seen\":1}");
        }
    }
}
