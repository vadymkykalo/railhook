package com.webhook.platform.common.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a Transformation written in JavaScript, in a sandbox, identically wherever it is called
 * from.
 *
 * <p>It lives in {@code railhook-common} rather than in either service because the preview a
 * person reads before saving and the transform that runs on a real Delivery have to be the same
 * code. The template language already learned that lesson the expensive way: it has two copies
 * that disagree about whether a bad JSONPath is an error.
 *
 * <h2>The contract</h2>
 *
 * <p>A script declares one entry point and nothing else is called:
 *
 * <pre>{@code
 * function handler(webhook) {
 *   return { payload: { id: webhook.eventId }, headers: { 'X-Kind': 'demo' } };
 * }
 * }</pre>
 *
 * <p>The argument carries the Event — {@code payload}, {@code eventType}, {@code eventId},
 * {@code timestamp} — and the delivery context — {@code direction}, {@code url},
 * {@code headers}. The return is an envelope: {@code payload} is the body to send,
 * {@code headers} is merged over the computed ones, and {@code cancel: true} drops the
 * delivery instead. {@code payload} is required unless {@code cancel} is.
 *
 * <p>The script runs in strict mode. There are no modules, no {@code require}, no
 * {@code module.exports} and no top-level {@code await}: one file, one function, synchronous.
 *
 * <h2>The sandbox</h2>
 *
 * <p>Five things are refused, and each of them is a test in
 * {@code JavaScriptTransformEngineTest}:
 *
 * <ul>
 *   <li><b>The host.</b> {@link HostAccess#NONE}, no class lookup, no
 *       {@code java}/{@code Packages} globals, {@link PolyglotAccess#NONE}. A script cannot
 *       name a Java class, so it cannot reach a repository, a connection pool or another
 *       tenant's row.</li>
 *   <li><b>The world.</b> No IO, no native access, no environment, no {@code load}. There is no
 *       filesystem and no socket to open — not a blocked one, an absent one.</li>
 *   <li><b>Threads.</b> {@code allowCreateThread(false)}, so a script runs on exactly the
 *       thread that called it and cannot outlive the call.</li>
 *   <li><b>Time.</b> A wall clock, enforced from outside by cancelling the Context. Guest code
 *       cannot catch a cancellation — a {@code while(true)} inside a {@code try} dies the same
 *       as one outside it.</li>
 *   <li><b>Memory.</b> An allocation ceiling for the run, sampled from the running thread's
 *       allocation counter and enforced the same way. Community GraalJS has no heap quota of
 *       its own, so this is the enforcement rather than a second line behind one.</li>
 * </ul>
 *
 * <p>Every failure is a {@link ScriptTransformException}, and every one of them is treated the
 * way a failed template is treated today: the attempt fails, it is retried, and the raw payload
 * is never sent instead.
 *
 * <h2>Why GraalJS</h2>
 *
 * <p>The alternative was an embedded QuickJS binding, which starts faster and is a fraction of
 * the size. It was rejected on the sandbox, not on the language: a JNI engine's runaway script
 * is a native frame on a worker thread, and stopping it means stopping the thread. GraalJS can
 * be cancelled from outside, from another thread, deterministically, which is the one property
 * this feature cannot do without. The cost is about 60 MB of jars in each image and roughly a
 * second of first-run warm-up per process; see the branch report for the measurements.
 */
@Slf4j
public class JavaScriptTransformEngine implements AutoCloseable {

    /** The one name a script has to define. */
    public static final String ENTRY_POINT = "handler";

    private static final String SOURCE_NAME = "transformation.js";

    /**
     * Deliberately one physical line. The author's line numbers are this wrapper's minus
     * {@link #LINE_OFFSET}, and that arithmetic is only true while the prelude stays on one line.
     *
     * <p>It opens by deleting the names GraalJS binds into a fresh realm — {@code java},
     * {@code Packages}, {@code Java}, {@code Polyglot}, {@code load} and their neighbours.
     * Every one of them is already useless behind {@link HostAccess#NONE} and a denied
     * filesystem, so this is not the barrier; it is what turns "that object refuses everything"
     * into "there is no such name", which is the answer a script author can act on. GraalJS has
     * options for the same effect, but they are marked experimental, and an experimental option
     * is a thing that can silently stop being honoured on an upgrade. A deleted global cannot.
     */
    private static final String PRELUDE_FORMAT =
            "['Packages','java','javax','javafx','org','com','edu','Java','Graal','Polyglot',"
            + "'load','loadWithNewGlobal','readFully','readLine','printErr','print','quit','exit',"
            + "'Worker','WebAssembly','process','require','module','exports','console']"
            + ".forEach(function(n){try{delete globalThis[n];}catch(e){}});"
            + "(function(){\"use strict\";globalThis.__rhC=[];globalThis.__rhT=false;var __rhMax=%d;"
            + "function __rhFmt(a){var p=[];for(var i=0;i<a.length;i++){var v=a[i];"
            + "if(typeof v==='string'){p.push(v);}else{try{p.push(JSON.stringify(v));}"
            + "catch(e){p.push(String(v));}}}var s=p.join(' ');"
            + "return s.length>2000?s.slice(0,2000)+'\\u2026':s;}"
            + "function __rhLog(l){return function(){if(globalThis.__rhC.length>=__rhMax)"
            + "{globalThis.__rhT=true;return;}globalThis.__rhC.push({level:l,message:__rhFmt(arguments)});};}"
            + "var console={log:__rhLog('log'),info:__rhLog('info'),warn:__rhLog('warn'),"
            + "error:__rhLog('error'),debug:__rhLog('debug')};\n";

    private static final String EPILOGUE =
            "\n;return{hasHandler:(typeof " + ENTRY_POINT + "==='function'),"
            + "run:function(__rhJson){var __rhR=" + ENTRY_POINT + "(JSON.parse(__rhJson));"
            + "return JSON.stringify({result:(__rhR===undefined?null:__rhR),"
            + "console:globalThis.__rhC,truncated:globalThis.__rhT});}};})()";

    /** One line of prelude sits above the author's first line. */
    private static final int LINE_OFFSET = 1;

    private final ObjectMapper objectMapper;
    private final ScriptLimits limits;
    private final ScheduledExecutorService watchdog;
    private final ExecutorService cancellers;
    private final com.sun.management.ThreadMXBean allocationCounter;

    private volatile Engine engine;
    private volatile boolean closed;

    public JavaScriptTransformEngine(ObjectMapper objectMapper, ScriptLimits limits) {
        this.objectMapper = objectMapper;
        this.limits = limits;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(daemon("rh-script-watchdog"));
        this.cancellers = Executors.newCachedThreadPool(daemon("rh-script-cancel"));
        this.allocationCounter = resolveAllocationCounter();
    }

    public ScriptLimits limits() {
        return limits;
    }

    // ── public surface ──────────────────────────────────────────────────────────────────

    /**
     * Compiles a script and checks it declares a {@code handler}, without ever calling it.
     *
     * <p>The top level still runs — a {@code const handler = ...} does not exist until it does —
     * so this is as sandboxed as a real run, and as limited.
     *
     * @throws ScriptTransformException when it will not compile or declares no handler
     */
    public void validate(String script) {
        execute(script, null);
    }

    /**
     * Runs a script against one Event and one delivery context.
     *
     * @throws ScriptTransformException for every outcome that is not a payload, including a
     *         cancellation the script did not ask for
     */
    public TransformOutcome run(String script, TransformRequest request) {
        TransformOutcome outcome = execute(script, request);
        if (outcome == null) {
            // Unreachable: a non-null request always produces an outcome or throws.
            throw new ScriptTransformException(ScriptTransformException.Reason.RUNTIME,
                    "The script produced no result");
        }
        return outcome;
    }

    @Override
    public void close() {
        closed = true;
        watchdog.shutdownNow();
        cancellers.shutdownNow();
        Engine current = engine;
        if (current != null) {
            try {
                current.close(true);
            } catch (RuntimeException e) {
                log.debug("Script engine would not close: {}", e.getMessage());
            }
        }
    }

    // ── the run itself ──────────────────────────────────────────────────────────────────

    /** {@code request == null} means "compile and check the contract, then stop". */
    private TransformOutcome execute(String script, TransformRequest request) {
        if (script == null || script.isBlank()) {
            throw new ScriptTransformException(ScriptTransformException.Reason.CONTRACT,
                    "The transformation has no script");
        }
        if (script.length() > limits.maxSourceChars()) {
            throw new ScriptTransformException(ScriptTransformException.Reason.SOURCE_TOO_LARGE,
                    "The script is " + script.length() + " characters; the limit is "
                            + limits.maxSourceChars());
        }

        Source source = Source.newBuilder("js", wrap(script), SOURCE_NAME).buildLiteral();
        long startedAt = System.nanoTime();

        Context context = newContext();
        Guard guard = arm(context);
        try {
            Value program = context.eval(source);
            if (!program.hasMembers() || !program.getMember("hasHandler").asBoolean()) {
                throw new ScriptTransformException(ScriptTransformException.Reason.CONTRACT,
                        "The script declares no `" + ENTRY_POINT + "` function. A transformation is "
                                + "one `function " + ENTRY_POINT + "(webhook) { ... }`.",
                        -1, -1, readConsoleQuietly(context), null);
            }
            if (request == null) {
                return null;
            }

            String resultJson = program.getMember("run").execute(inputJson(request)).asString();
            return interpret(resultJson, elapsedMillis(startedAt));

        } catch (PolyglotException e) {
            throw translate(e, guard, context);
        } finally {
            guard.disarm();
            closeQuietly(context);
        }
    }

    private String wrap(String script) {
        return PRELUDE_FORMAT.formatted(limits.maxConsoleLines()) + script + EPILOGUE;
    }

    /** The guest's whole world, as JSON. It parses this itself; nothing crosses as a host object. */
    private String inputJson(TransformRequest request) {
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("payload", objectMapper.readTree(request.payload()));
            input.put("eventType", request.eventType());
            input.put("eventId", request.eventId());
            input.put("timestamp", request.timestamp() == null ? null
                    : DateTimeFormatter.ISO_INSTANT.format(request.timestamp()));
            input.put("direction", request.direction());
            input.put("url", request.url());
            input.put("headers", request.headers());
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            throw new ScriptTransformException(ScriptTransformException.Reason.RUNTIME,
                    "The event payload is not valid JSON: " + e.getMessage());
        }
    }

    private TransformOutcome interpret(String resultJson, long durationMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(resultJson);
        } catch (Exception e) {
            throw new ScriptTransformException(ScriptTransformException.Reason.RUNTIME,
                    "The script returned something that is not JSON: " + e.getMessage());
        }

        List<ScriptConsoleLine> console = readConsole(root.path("console"));
        boolean truncated = root.path("truncated").asBoolean(false);
        JsonNode result = root.path("result");

        if (result.isNull() || result.isMissingNode() || !result.isObject()) {
            throw new ScriptTransformException(ScriptTransformException.Reason.CONTRACT,
                    "`" + ENTRY_POINT + "` must return an object with a `payload` property, or "
                            + "`{ cancel: true }`.",
                    -1, -1, console, null);
        }

        if (result.path("cancel").asBoolean(false)) {
            return TransformOutcome.builder()
                    .cancelled(true)
                    .cancelReason(result.path("cancelReason").isTextual()
                            ? result.get("cancelReason").asText() : null)
                    .console(console)
                    .consoleTruncated(truncated)
                    .durationMs(durationMs)
                    .build();
        }

        JsonNode payload = result.path("payload");
        if (payload.isMissingNode()) {
            throw new ScriptTransformException(ScriptTransformException.Reason.CONTRACT,
                    "`" + ENTRY_POINT + "` returned an object with no `payload` and no `cancel`. "
                            + "Return `{ payload: ... }` to send a body, or `{ cancel: true }` to drop "
                            + "the delivery.",
                    -1, -1, console, null);
        }

        String body = payload.toString();
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxOutputBytes()) {
            throw new ScriptTransformException(ScriptTransformException.Reason.OUTPUT_TOO_LARGE,
                    "The script returned " + bytes + " bytes; the limit is " + limits.maxOutputBytes(),
                    -1, -1, console, null);
        }

        return TransformOutcome.builder()
                .payload(body)
                .headers(readHeaders(result.path("headers")))
                .console(console)
                .consoleTruncated(truncated)
                .durationMs(durationMs)
                .build();
    }

    private Map<String, String> readHeaders(JsonNode headers) {
        if (!headers.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        headers.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isNull() && !value.isContainerNode()) {
                out.put(entry.getKey(), value.asText());
            }
        });
        return out;
    }

    private List<ScriptConsoleLine> readConsole(JsonNode console) {
        List<ScriptConsoleLine> lines = new ArrayList<>();
        if (console.isArray()) {
            for (JsonNode line : console) {
                lines.add(new ScriptConsoleLine(
                        line.path("level").asText("log"), line.path("message").asText("")));
            }
        }
        return lines;
    }

    /**
     * Reads back whatever the script logged before it died.
     *
     * <p>Best effort by construction: a cancelled Context cannot be evaluated again, so a script
     * killed on time or memory comes back with nothing. For everything else — a throw, a
     * reference to something the sandbox does not have — these lines are usually the only clue
     * the author gets.
     */
    private List<ScriptConsoleLine> readConsoleQuietly(Context context) {
        try {
            return readConsole(objectMapper.readTree(
                    context.eval("js", "JSON.stringify(globalThis.__rhC||[])").asString()));
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── failure translation ─────────────────────────────────────────────────────────────

    private ScriptTransformException translate(PolyglotException e, Guard guard, Context context) {
        ScriptTransformException.Reason tripped = guard.tripped();
        if (tripped != null) {
            return new ScriptTransformException(tripped, switch (tripped) {
                case TIMEOUT -> "The script was still running after " + limits.timeout().toMillis()
                        + " ms and was stopped";
                case MEMORY -> "The script allocated more than "
                        + (limits.maxMemoryBytes() / (1024 * 1024)) + " MB and was stopped";
                default -> "The script was stopped";
            }, -1, -1, List.of(), e);
        }
        if (e.isCancelled() || e.isInterrupted()) {
            // Cancelled without the guard tripping means the engine itself is going away.
            return new ScriptTransformException(ScriptTransformException.Reason.TIMEOUT,
                    "The script was stopped before it finished", -1, -1, List.of(), e);
        }

        int[] location = locate(e);
        ScriptTransformException.Reason reason = e.isSyntaxError()
                ? ScriptTransformException.Reason.SYNTAX
                : ScriptTransformException.Reason.RUNTIME;
        return new ScriptTransformException(reason, message(e), location[0], location[1],
                reason == ScriptTransformException.Reason.SYNTAX ? List.of() : readConsoleQuietly(context),
                e);
    }

    private String message(PolyglotException e) {
        String raw = e.getMessage();
        return raw == null || raw.isBlank() ? e.getClass().getSimpleName() : raw;
    }

    /** {@code {line, column}} in the author's own script, or {@code {-1, -1}}. */
    private int[] locate(PolyglotException e) {
        SourceSection section = e.getSourceLocation();
        if (section == null) {
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame() && frame.getSourceLocation() != null) {
                    section = frame.getSourceLocation();
                    break;
                }
            }
        }
        if (section == null || !section.isAvailable()) {
            return new int[] {-1, -1};
        }
        int line = section.getStartLine() - LINE_OFFSET;
        return line >= 1 ? new int[] {line, section.getStartColumn()} : new int[] {-1, -1};
    }

    // ── the sandbox ─────────────────────────────────────────────────────────────────────

    private Context newContext() {
        return newContext(sharedEngine());
    }

    private Context newContext(Engine on) {
        return Context.newBuilder("js")
                .engine(on)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(className -> false)
                .allowHostClassLoading(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowValueSharing(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                // No `allowIO`: the default is a filesystem that denies everything, which is
                // what we want. Naming it would only invite someone to pass something else.
                .option("js.ecmascript-version", "2023")
                .build();
    }

    /**
     * Built once, lazily. It carries the parsed-code cache, so the second run of a script costs
     * milliseconds where the first costs about a second — and a deployment that never runs a
     * JavaScript transformation never pays for it at all.
     */
    private Engine sharedEngine() {
        Engine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (engine == null) {
                if (closed) {
                    throw new ScriptTransformException(ScriptTransformException.Reason.UNAVAILABLE,
                            "The script engine is shutting down");
                }
                try {
                    engine = Engine.newBuilder("js")
                            // Stock JDKs have no Graal compiler, so Truffle runs interpreted.
                            // That is a performance note, not a correctness one, and it is not
                            // worth a WARN on every boot.
                            .option("engine.WarnInterpreterOnly", "false")
                            .build();
                } catch (LinkageError err) {
                    throw new ScriptTransformException(ScriptTransformException.Reason.UNAVAILABLE,
                            "JavaScript transformations are not available in this build: "
                                    + err.getMessage());
                }
                warmUp(engine);
            }
            return engine;
        }
    }

    /**
     * Runs one trivial script so the first real one does not pay for the language.
     *
     * <p>Not an optimisation. Bringing GraalJS up costs about a second of wall clock and tens of
     * megabytes of allocation, and both of those are charged to whichever run happens to be
     * first — which, under a two-second timeout and a memory ceiling, means the first
     * transformation after every deploy fails on limits it never came close to. Paid here
     * instead, once, outside any watchdog, on whoever asked first.
     */
    private void warmUp(Engine warm) {
        long startedAt = System.nanoTime();
        try (Context context = newContext(warm)) {
            Value program = context.eval(Source.newBuilder("js",
                    wrap("function " + ENTRY_POINT + "(webhook) { return { payload: webhook.payload }; }"),
                    SOURCE_NAME).buildLiteral());
            program.getMember("run").execute("{\"payload\":{},\"headers\":{}}");
            log.info("JavaScript transformation engine ready in {} ms", elapsedMillis(startedAt));
        } catch (RuntimeException e) {
            log.warn("JavaScript transformation engine warm-up failed: {}", e.getMessage());
        }
    }

    // ── the watchdog ────────────────────────────────────────────────────────────────────

    private Guard arm(Context context) {
        Guard guard = new Guard(context, Thread.currentThread().getId(),
                System.nanoTime() + limits.timeout().toNanos(),
                allocatedBytes(Thread.currentThread().getId()));
        long tick = Math.max(5, Math.min(25, limits.timeout().toMillis() / 8));
        guard.future = watchdog.scheduleAtFixedRate(guard::check, tick, tick, TimeUnit.MILLISECONDS);
        return guard;
    }

    private long allocatedBytes(long threadId) {
        if (allocationCounter == null) {
            return 0;
        }
        try {
            long bytes = allocationCounter.getThreadAllocatedBytes(threadId);
            return bytes < 0 ? 0 : bytes;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Watches one run, from outside it.
     *
     * <p>Both ceilings are enforced the same way — {@code Context.close(true)}, called from
     * another thread, which unwinds the guest wherever it is. That is the whole reason this
     * engine is GraalJS: a cancellation cannot be caught, delayed or retried by guest code, so
     * a {@code while (true)} inside a {@code try/catch/finally} ends exactly as fast as one
     * standing on its own.
     */
    private final class Guard {

        private final Context context;
        private final long threadId;
        private final long deadlineNanos;
        private final long allocationStart;
        private volatile ScriptTransformException.Reason tripped;
        private volatile ScheduledFuture<?> future;

        private Guard(Context context, long threadId, long deadlineNanos, long allocationStart) {
            this.context = context;
            this.threadId = threadId;
            this.deadlineNanos = deadlineNanos;
            this.allocationStart = allocationStart;
        }

        private void check() {
            if (tripped != null) {
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                trip(ScriptTransformException.Reason.TIMEOUT);
            } else if (allocatedBytes(threadId) - allocationStart > limits.maxMemoryBytes()) {
                trip(ScriptTransformException.Reason.MEMORY);
            }
        }

        private void trip(ScriptTransformException.Reason reason) {
            tripped = reason;
            cancel();
            // close(true) blocks until the guest has unwound, so it must not run on the
            // watchdog thread: one stuck script would stop every other script being watched.
            try {
                cancellers.execute(() -> closeQuietly(context));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                closeQuietly(context);
            }
        }

        private ScriptTransformException.Reason tripped() {
            return tripped;
        }

        private void disarm() {
            cancel();
        }

        private void cancel() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    private static void closeQuietly(Context context) {
        try {
            context.close(true);
        } catch (RuntimeException | LinkageError e) {
            log.debug("Script context would not close: {}", e.getMessage());
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static com.sun.management.ThreadMXBean resolveAllocationCounter() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            sun.setThreadAllocatedMemoryEnabled(true);
            return sun;
        }
        log.warn("This JVM does not report per-thread allocation, so the script memory ceiling "
                + "cannot be enforced; the wall-clock timeout still applies");
        return null;
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
