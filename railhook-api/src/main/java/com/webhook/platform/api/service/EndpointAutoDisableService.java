package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns off a target that has answered nothing but failures for a whole window.
 *
 * <p>The gap this closes: a dead receiver used to burn its retry budget forever. Every new
 * Event started a fresh Ladder, each Ladder ran its seven attempts over days, the circuit
 * breaker deferred a few of them and then forgot, and nothing anywhere said "this endpoint has
 * been dead since Tuesday". The operator saw thousands of Failed Messages and no cause.
 *
 * <p>The decision needs two things the worker cannot reach — the alert and the mail — so the
 * work is split: the worker keeps the run of failures on the target's row at the shared attempt
 * seam, and this reads it. The split also means the hot path costs one UPDATE on a failure and
 * nothing at all on a success, rather than a query over {@code deliveries} per endpoint.
 *
 * <p>Both directions, because the shape fits both exactly: an Endpoint and a Destination each
 * have an {@code enabled} flag the store already honours, and each has the same four columns.
 *
 * <h2>What happens to work already queued</h2>
 *
 * Deliveries and Forwards already made out to the target do not vanish and do not silently
 * fail. The stores hand them to the <strong>DLQ</strong> — Failed Messages — rather than to
 * FAILED, precisely because this is Railhook's decision and not the owner's: once the receiver
 * is fixed and the target re-enabled, they are all there to retry. A target its owner turned
 * off keeps the old behaviour and fails them, which is what "I meant to stop this" means.
 */
@Service
@Slf4j
public class EndpointAutoDisableService {

    private final EndpointRepository endpointRepository;
    private final IncomingDestinationRepository destinationRepository;
    private final IncomingSourceRepository sourceRepository;
    private final EndpointAutoDisableNotifier notifier;
    private final Clock clock;
    private final boolean enabled;
    private final Duration window;
    private final int minFailures;
    private final Pageable batch;

    public EndpointAutoDisableService(
            EndpointRepository endpointRepository,
            IncomingDestinationRepository destinationRepository,
            IncomingSourceRepository sourceRepository,
            EndpointAutoDisableNotifier notifier,
            Clock clock,
            @Value("${endpoint.auto-disable.enabled:true}") boolean enabled,
            @Value("${endpoint.auto-disable.after-hours:72}") long afterHours,
            @Value("${endpoint.auto-disable.min-failures:10}") int minFailures,
            @Value("${endpoint.auto-disable.batch-size:200}") int batchSize) {
        // Refused rather than clamped. A window of zero would disable every target that has a
        // single outstanding failure the moment the sweep runs, and a minimum of zero would do
        // it on the first one — both are what a typo in an env var looks like, and neither is
        // something an operator could have meant.
        if (enabled && afterHours < 1) {
            throw new IllegalArgumentException(
                    "endpoint.auto-disable.after-hours is " + afterHours
                            + "; a window shorter than an hour would disable a target on a blip. "
                            + "Set endpoint.auto-disable.enabled=false to turn the feature off instead.");
        }
        if (enabled && minFailures < 1) {
            throw new IllegalArgumentException(
                    "endpoint.auto-disable.min-failures is " + minFailures
                            + "; at least one failure has to have happened.");
        }
        this.endpointRepository = endpointRepository;
        this.destinationRepository = destinationRepository;
        this.sourceRepository = sourceRepository;
        this.notifier = notifier;
        this.clock = clock;
        this.enabled = enabled;
        this.window = Duration.ofHours(afterHours);
        this.minFailures = minFailures;
        this.batch = PageRequest.of(0, Math.max(1, batchSize));
        if (!enabled) {
            log.info("Auto-disabling a persistently failing target is off");
        }
    }

    /**
     * Runs every few minutes, which is as often as it needs to: the window is measured in days,
     * so the sweep's own cadence adds nothing anybody would notice.
     */
    @SystemTenant("endpoints and destinations of every organization; each is notified inside its own")
    @Scheduled(fixedDelayString = "${endpoint.auto-disable.interval-ms:300000}")
    @SchedulerLock(name = "endpoint_auto_disable", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void sweep() {
        if (!enabled) {
            return;
        }
        Instant cutoff = clock.instant().minus(window);
        disableEndpoints(cutoff);
        disableDestinations(cutoff);
    }

    private void disableEndpoints(Instant cutoff) {
        List<Endpoint> candidates = endpointRepository.findAutoDisableCandidates(cutoff, minFailures, batch);
        for (Endpoint endpoint : candidates) {
            // Already auto-disabled, from a row the query read before another pass turned it off.
            if (endpoint.getAutoDisabledAt() != null) {
                continue;
            }
            Instant at = clock.instant();
            String reason = reason(endpoint.getFailingSince(), endpoint.getConsecutiveFailures());
            int applied;
            try {
                applied = endpointRepository.autoDisable(endpoint.getId(), at, reason);
            } catch (Exception e) {
                // One row another transaction holds must not cost the rest of the sweep.
                log.warn("Endpoint {} could not be auto-disabled: {}", endpoint.getId(), e.toString());
                continue;
            }
            if (applied == 0) {
                // Turned back on, or turned off, between the query and here. Either way it is
                // not this sweep's to announce.
                log.debug("Endpoint {} changed under the sweep; not disabling it", endpoint.getId());
                continue;
            }
            log.warn("Endpoint {} ({}) auto-disabled: failing since {}, {} consecutive failures",
                    endpoint.getId(), endpoint.getUrl(), endpoint.getFailingSince(),
                    endpoint.getConsecutiveFailures());
            // The row is written; these make the in-memory copy say the same thing, because it
            // is what the notification is composed from.
            endpoint.setEnabled(false);
            endpoint.setAutoDisabledAt(at);
            endpoint.setAutoDisabledReason(reason);
            announce(() -> notifier.endpointDisabled(endpoint), "endpoint", endpoint.getId());
        }
    }

    private void disableDestinations(Instant cutoff) {
        List<IncomingDestination> candidates =
                destinationRepository.findAutoDisableCandidates(cutoff, minFailures, batch);
        for (IncomingDestination destination : candidates) {
            if (destination.getAutoDisabledAt() != null) {
                continue;
            }
            Instant at = clock.instant();
            String reason = reason(destination.getFailingSince(), destination.getConsecutiveFailures());
            int applied;
            try {
                applied = destinationRepository.autoDisable(destination.getId(), at, reason);
            } catch (Exception e) {
                log.warn("Destination {} could not be auto-disabled: {}",
                        destination.getId(), e.toString());
                continue;
            }
            if (applied == 0) {
                log.debug("Destination {} changed under the sweep; not disabling it", destination.getId());
                continue;
            }
            log.warn("Destination {} ({}) auto-disabled: failing since {}, {} consecutive failures",
                    destination.getId(), destination.getUrl(), destination.getFailingSince(),
                    destination.getConsecutiveFailures());
            destination.setEnabled(false);
            destination.setAutoDisabledAt(at);
            destination.setAutoDisabledReason(reason);
            // An alert hangs off a Project, and a Destination reaches one only through its Source.
            UUID projectId = sourceRepository.findById(destination.getIncomingSourceId())
                    .map(IncomingSource::getProjectId)
                    .orElse(null);
            if (projectId == null) {
                log.warn("Destination {} was auto-disabled but its source {} is gone, so nobody was told",
                        destination.getId(), destination.getIncomingSourceId());
                continue;
            }
            announce(() -> notifier.destinationDisabled(destination, projectId),
                    "destination", destination.getId());
        }
    }

    /**
     * Telling the owner is not part of the decision. The target is dead whether or not the mail
     * server is; a throw here used to be the kind of thing that leaves a half-applied sweep, and
     * the row is already committed by the time this runs.
     */
    private void announce(Runnable announcement, String what, UUID id) {
        try {
            announcement.run();
        } catch (Exception e) {
            log.error("{} {} was auto-disabled but the owner could not be told: {}",
                    what, id, e.toString(), e);
        }
    }

    private String reason(Instant failingSince, Integer failures) {
        return String.format(
                "No delivery to this target has succeeded since %s — %d consecutive failures over "
                        + "more than %d hours. Railhook stopped sending to it. Fix the receiver and "
                        + "turn it back on; anything that was queued is waiting in Failed Messages.",
                failingSince, failures == null ? 0 : failures, window.toHours());
    }
}
