package com.webhook.platform.worker.attempt;

import java.time.Instant;

/** How an Attempt ended. Each {@link AttemptStore} maps it onto its own row model. */
public sealed interface Finalization {

    record Succeeded() implements Finalization {
    }

    /** Nothing was sent (rate limit, concurrency cap, open breaker). The Ladder does not advance. */
    record Deferred(Instant until, String reason) implements Finalization {
    }

    record Retry(Instant at, String reason) implements Finalization {
    }

    /** Goes to the DLQ, where a person can retry it. */
    record Abandoned(String reason) implements Finalization {
    }

    /** No Attempt could help and no person is offered a retry. */
    record TerminallyFailed(String reason) implements Finalization {
    }

    /**
     * A Transformation returned {@code { cancel: true }}. Kept apart from TerminallyFailed: one is
     * an error rate somebody is paged for, the other a filter doing its job.
     */
    record Cancelled(String reason) implements Finalization {
    }
}
