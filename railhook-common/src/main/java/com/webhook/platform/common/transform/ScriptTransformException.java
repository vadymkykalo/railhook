package com.webhook.platform.common.transform;

import java.util.List;

/**
 * A script that did not produce a payload, for any reason.
 *
 * <p>Every one of these is treated the way a failed template is treated today: the attempt fails
 * and is retried, and the raw payload never goes out in its place. A transformation whose job is
 * to strip a field is exactly the transformation whose silent misfire nobody notices.
 *
 * <p>The {@link #reason()} is what the author is told and what the operator meters on; it is not
 * a retry decision. {@link #line()} is the line in the author's own script, already corrected
 * for the wrapper the engine puts around it.
 */
public class ScriptTransformException extends RuntimeException {

    /** Why the run produced nothing. */
    public enum Reason {
        /** The script would not compile. */
        SYNTAX,
        /** It compiled, but declares no {@code handler}, or returned something that is not one. */
        CONTRACT,
        /** It ran and threw, or reached for something the sandbox does not have. */
        RUNTIME,
        /** It was still running when the wall clock ran out. */
        TIMEOUT,
        /** It allocated past the ceiling. */
        MEMORY,
        /** It returned a body larger than the ceiling. */
        OUTPUT_TOO_LARGE,
        /** The script itself is larger than the engine will compile. */
        SOURCE_TOO_LARGE,
        /** The engine is not on this classpath. A deployment fault, not an author's. */
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

    /** 1-based line in the author's script, or -1 when the failure has no location. */
    public int line() {
        return line;
    }

    /** 1-based column, or -1. */
    public int column() {
        return column;
    }

    /** Whatever the script logged before it failed — usually the only clue there is. */
    public List<ScriptConsoleLine> console() {
        return console;
    }
}
