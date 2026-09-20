package com.webhook.platform.worker.attempt;

import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.IncomingDestinationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Keeps, on the target's own row, how long it has been answering nothing but failures — the
 * memory the circuit breaker deliberately does not have.
 *
 * <p>The breaker trips in seconds and forgets in minutes, which is what makes it safe to let it
 * throttle a busy receiver. Turning an Endpoint or a Destination <em>off</em> is not a decision
 * to take on two minutes of Redis, so the run of failures goes in Postgres and the api's sweep
 * is what acts on it.
 *
 * <p>Called from both {@link AttemptStore}s, so the two directions count the same way. It is
 * the only thing in the worker that writes to {@code endpoints} or {@code incoming_destinations},
 * and it has two statements: one that extends a run, one that ends it. The second matches no
 * rows on a target that was already healthy, so a deployment whose receivers all work pays for
 * this feature with no writes at all.
 */
@Component
@Slf4j
public class TargetFailureRecorder {

    private final EndpointRepository endpointRepository;
    private final IncomingDestinationRepository destinationRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final boolean enabled;

    public TargetFailureRecorder(
            EndpointRepository endpointRepository,
            IncomingDestinationRepository destinationRepository,
            TransactionTemplate transactionTemplate,
            Clock clock,
            @Value("${endpoint.auto-disable.enabled:true}") boolean enabled) {
        this.endpointRepository = endpointRepository;
        this.destinationRepository = destinationRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.enabled = enabled;
        if (!enabled) {
            log.info("Auto-disabling a persistently failing target is off: the run of failures is "
                    + "not tracked and nothing is turned off automatically");
        }
    }

    /**
     * Records what one Attempt said about an Endpoint.
     *
     * <p>The whole feature is behind one switch, and the switch is read here rather than in the
     * sweep so that turning it off costs nothing on the delivery path. Turning it off mid-run
     * leaves whatever counters a target had, and turning it back on continues from there — the
     * only behaviour that needs no backfill either way.
     */
    public void endpointAttempt(UUID endpointId, boolean succeeded) {
        if (!enabled) {
            return;
        }
        write(succeeded
                        ? () -> endpointRepository.recordAttemptSucceeded(endpointId)
                        : () -> endpointRepository.recordAttemptFailed(endpointId, clock.instant()),
                "endpoint", endpointId);
    }

    /** @see #endpointAttempt */
    public void destinationAttempt(UUID destinationId, boolean succeeded) {
        if (!enabled) {
            return;
        }
        write(succeeded
                        ? () -> destinationRepository.recordAttemptSucceeded(destinationId)
                        : () -> destinationRepository.recordAttemptFailed(destinationId, clock.instant()),
                "destination", destinationId);
    }

    /**
     * Its own transaction, not the caller's: this runs after an outcome has already been
     * finalised, and a counter that will not increment must never roll anything back.
     */
    private void write(IntSupplier statement, String what, UUID id) {
        try {
            transactionTemplate.executeWithoutResult(tx -> statement.getAsInt());
        } catch (Exception e) {
            log.warn("Could not record the attempt outcome against {} {}: {}", what, id, e.getMessage());
        }
    }
}
