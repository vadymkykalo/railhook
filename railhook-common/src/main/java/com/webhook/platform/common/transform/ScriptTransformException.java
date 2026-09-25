package com.webhook.platform.common.transform;

import java.util.List;

/**
 * Any script run that produced no payload. The attempt fails and retries; the raw payload is
 * never sent instead, since a transformation that strips a field is the one whose misfire nobody
 * notices. {@link #reason()} is for the author and for metrics, not a retry decision.
 */
public class ScriptTransformException extends RuntimeException {

    public enum Reason {
        SYNTAX,
        /** No {@code handler}, or it returned something that is not an envelope. */
        CONTRACT,
        RUNTIME,
        TIMEOUT,
        MEMORY,
        OUTPUT_TOO_LARGE,
        SOURCE_TOO_LARGE,
        /** The engine is not on this classpath: a deployment fault, not the author's. */
        UNAVAILABLE
    }

    private final transient Reason reason;
    private final int line;
    private final int column;
    private final transient List<ScriptConsoleLine> console;

    public ScriptTransformException(Reason reason, String message) {
        this(reason, message, -1, -1, List.of(), null);
    }

    public ScriptTransformException(Reason reason, String message, int line, int column,
            List<ScriptConsoleLine> console, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.line = line;
        this.column = column;
        this.console = console == null ? List.of() : List.copyOf(console);
    }

    public Reason reason() {
        return reason;
    }

    /** 1-based, already corrected for the engine's wrapper; -1 when unknown. */
    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public List<ScriptConsoleLine> console() {
        return console;
    }
}
