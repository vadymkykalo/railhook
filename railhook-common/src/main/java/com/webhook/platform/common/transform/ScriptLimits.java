package com.webhook.platform.common.transform;

import java.time.Duration;

/**
 * The ceilings a script runs under. All five are hard: crossing any of them ends the run with a
 * {@link ScriptTransformException}, and none of them is something guest code can raise, catch or
 * negotiate.
 *
 * <p>They are deployment settings rather than per-transformation ones on purpose. A script is
 * written by a tenant and run on shared workers, so the limits belong to whoever operates the
 * workers — see {@code TRANSFORM_SCRIPT_*} in {@code .env.dist}.
 *
 * @param timeout          wall clock for one run, counted from the first guest instruction
 * @param maxMemoryBytes   how much a single run may allocate before it is cancelled. Measured as
 *                         the running thread's allocation counter, not as live heap: a script
 *                         that churns a gigabyte through the collector is as dangerous to a
 *                         worker as one that holds it.
 * @param maxOutputBytes   the largest body a script may return
 * @param maxConsoleLines  how many {@code console.*} calls are kept; the rest are dropped and
 *                         the outcome says so
 * @param maxSourceChars   the largest script the engine will even compile
 */
public record ScriptLimits(
        Duration timeout,
        long maxMemoryBytes,
        int maxOutputBytes,
        int maxConsoleLines,
        int maxSourceChars) {

    public ScriptLimits {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Script timeout must be positive");
        }
        if (maxMemoryBytes <= 0 || maxOutputBytes <= 0 || maxConsoleLines <= 0 || maxSourceChars <= 0) {
            throw new IllegalArgumentException("Script limits must all be positive");
        }
    }

    /** What the platform ships with; {@code .env.dist} documents each one. */
    public static ScriptLimits defaults() {
        return new ScriptLimits(Duration.ofSeconds(2), 128L * 1024 * 1024, 1024 * 1024, 100, 65536);
    }
}
