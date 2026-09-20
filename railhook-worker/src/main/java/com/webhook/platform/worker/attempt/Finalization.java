package com.webhook.platform.worker.attempt;

import java.time.Instant;

/**
 * How an Attempt ended, as the Runner sees it. The {@link AttemptStore} turns one of these
 * into whatever its own row model requires.
 *
 * <p>Five outcomes, and the distinctions between them are the ones that were getting lost
 * when each direction spelled this out for itself:
 *
 * <ul>
 *   <li>{@link Deferred} means <em>nothing was sent</em>. The Retry Ladder does not advance.</li>
 *   <li>{@link Retry} means an Attempt was made and failed, and another is owed.</li>
 *   <li>{@link Abandoned} means the Ladder is exhausted — DLQ.</li>
 *   <li>{@link TerminallyFailed} means another Attempt could not possibly help: an
 *       unresolvable URL, a disabled or deleted target, an unusable Ladder.</li>
 *   <li>{@link Cancelled} means a Transformation said not to send it. Also terminal, and
 *       deliberately not the same as TerminallyFailed: nothing went wrong.</li>
 * </ul>
 */
public sealed interface Finalization {

    /** 2xx. */
    record Succeeded() implements Finalization {
    }

    /**
     * Turned away before the Attempt was made — rate limit, concurrency cap, open circuit
     * breaker. Hand the obligation back to the ladder at {@code until} without consuming an
     * attempt.
     */
    record Deferred(Instant until, String reason) implements Finalization {
    }

    /** The Attempt failed and the Ladder still has room. Another is due at {@code at}. */
    record Retry(Instant at, String reason) implements Finalization {
    }

    /** The Ladder is exhausted. */
    record Abandoned(String reason) implements Finalization {
    }

    /** Retrying cannot help. */
    record TerminallyFailed(String reason) implements Finalization {
    }

    /**
     * A Transformation returned {@code { cancel: true }}. Nothing was sent, nothing is owed, and
     * the next Attempt would run the same script over the same payload and reach the same answer.
     *
     * <p>Separate from {@link TerminallyFailed} because the two are different things to everyone
     * downstream: an error rate somebody is paged for, and a filter doing its job.
     */
    record Cancelled(String reason) implements Finalization {
    }
}
