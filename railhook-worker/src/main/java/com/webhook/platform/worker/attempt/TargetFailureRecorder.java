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
 * Records a target's run of failures in Postgres for the api's auto-disable sweep. The circuit
 * breaker forgets in minutes, which is too short a memory for turning a target off. A healthy
 * target costs no writes.
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

    /** The feature switch is read here so that turning it off costs nothing on the delivery path. */
    public void endpointAttempt(UUID endpointId, boolean succeeded) {
        if (!enabled) {
            return;
        }
        write(succeeded
                        ? () -> endpointRepository.recordAttemptSucceeded(endpointId)
                        : () -> endpointRepository.recordAttemptFailed(endpointId, clock.instant()),
                "endpoint", endpointId);
    }

    public void destinationAttempt(UUID destinationId, boolean succeeded) {
        if (!enabled) {
            return;
        }
        write(succeeded
                        ? () -> destinationRepository.recordAttemptSucceeded(destinationId)
                        : () -> destinationRepository.recordAttemptFailed(destinationId, clock.instant()),
                "destination", destinationId);
    }

    /** Own transaction: runs after finalisation, and a failed counter must not roll that back. */
    private void write(IntSupplier statement, String what, UUID id) {
        try {
            transactionTemplate.executeWithoutResult(tx -> statement.getAsInt());
        } catch (Exception e) {
            log.warn("Could not record the attempt outcome against {} {}: {}", what, id, e.getMessage());
        }
    }
}
