package com.webhook.platform.api.service.demo;

import com.webhook.platform.api.service.demo.DemoHistory.AttemptRow;
import com.webhook.platform.api.service.demo.DemoHistory.DeliveryRow;
import com.webhook.platform.api.service.demo.DemoHistory.ForwardRow;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo's generated history. What matters most is not that it looks real but that none of it is
 * work: the worker must never find a demo row it would attempt.
 */
class DemoHistoryTest {

    private static final Instant NOW = Instant.parse("2026-09-19T14:30:00Z");

    private final DemoHistory history = DemoHistory.generate(NOW);

    @Test
    void theSameMomentGivesTheSameHistory() {
        // What makes regenerating it idempotent: two replicas, or two runs, write the same rows.
        DemoHistory again = DemoHistory.generate(NOW);

        assertThat(again.events).isEqualTo(history.events);
        assertThat(again.deliveries).isEqualTo(history.deliveries);
        assertThat(again.attempts).isEqualTo(history.attempts);
        assertThat(again.incomingEvents).isEqualTo(history.incomingEvents);
        assertThat(again.forwards).isEqualTo(history.forwards);
    }

    @Test
    void everyDeliveryAndForwardIsFinished() {
        assertThat(history.deliveries).extracting(DeliveryRow::status).containsOnly("SUCCESS", "DLQ");
        assertThat(history.forwards).extracting(ForwardRow::status).containsOnly("SUCCESS", "FAILED");
        // A FAILED forward attempt is only ever one that a later attempt superseded.
        Map<String, Long> attemptsPerForward = history.forwards.stream().collect(Collectors.groupingBy(
                f -> f.incomingEventId() + "/" + f.destinationId(), Collectors.counting()));
        for (ForwardRow failed : history.forwards.stream().filter(f -> f.status().equals("FAILED")).toList()) {
            assertThat(attemptsPerForward.get(failed.incomingEventId() + "/" + failed.destinationId())).isEqualTo(2);
        }
    }

    @Test
    void nothingHappensInTheFuture() {
        Instant settled = NOW.minus(DemoHistory.SETTLED).plusSeconds(60);
        Instant start = NOW.minus(DemoHistory.WINDOW).minus(Duration.ofMinutes(1));
        Stream.of(
                history.events.stream().map(DemoHistory.EventRow::createdAt),
                history.attempts.stream().map(AttemptRow::createdAt),
                history.deliveries.stream().map(DeliveryRow::lastAttemptAt),
                history.incomingEvents.stream().map(DemoHistory.IncomingEventRow::receivedAt),
                history.forwards.stream().map(ForwardRow::finishedAt)
        ).flatMap(s -> s).forEach(at -> assertThat(at).isBetween(start, settled));
    }

    @Test
    void theLastDayHasTrafficRetriesAndFailedMessages() {
        Instant dayAgo = NOW.minus(Duration.ofHours(24));
        List<DeliveryRow> lastDay = history.deliveries.stream().filter(d -> d.createdAt().isAfter(dayAgo)).toList();

        assertThat(history.events).hasSizeGreaterThan(150);
        assertThat(lastDay).hasSizeGreaterThan(150);
        assertThat(lastDay).anyMatch(d -> d.status().equals("SUCCESS") && d.attemptCount() > 1);
        assertThat(lastDay).anyMatch(d -> d.status().equals("DLQ"));
        assertThat(history.incomingEvents).extracting(DemoHistory.IncomingEventRow::sourceId)
                .contains(DemoCatalog.STRIPE.id(), DemoCatalog.GITHUB.id());
    }

    @Test
    void attemptsAddUpToTheirDeliveries() {
        Map<UUID, List<AttemptRow>> byDelivery = history.attempts.stream()
                .collect(Collectors.groupingBy(AttemptRow::deliveryId));
        for (DeliveryRow delivery : history.deliveries) {
            List<AttemptRow> attempts = byDelivery.get(delivery.id());
            assertThat(attempts).hasSize(delivery.attemptCount());
            assertThat(delivery.attemptCount()).isLessThanOrEqualTo(delivery.maxAttempts());
            AttemptRow last = attempts.get(attempts.size() - 1);
            boolean lastSucceeded = last.httpStatus() != null && last.httpStatus() / 100 == 2;
            assertThat(lastSucceeded).isEqualTo(delivery.status().equals("SUCCESS"));
            if (delivery.status().equals("DLQ")) {
                assertThat(delivery.attemptCount()).isEqualTo(delivery.maxAttempts());
            }
        }
    }

    @Test
    void everyIdIsDistinct() {
        Set<UUID> ids = Stream.of(
                history.events.stream().map(DemoHistory.EventRow::id),
                history.deliveries.stream().map(DeliveryRow::id),
                history.attempts.stream().map(AttemptRow::id),
                history.incomingEvents.stream().map(DemoHistory.IncomingEventRow::id),
                history.forwards.stream().map(ForwardRow::id)
        ).flatMap(s -> s).collect(Collectors.toSet());
        int rows = history.events.size() + history.deliveries.size() + history.attempts.size()
                + history.incomingEvents.size() + history.forwards.size();
        assertThat(ids).hasSize(rows);
    }

    @Test
    void everyTargetIsOnAReservedDocumentationHost() {
        Stream.concat(DemoCatalog.ENDPOINTS.stream().map(DemoCatalog.DemoEndpoint::url),
                        DemoCatalog.DESTINATIONS.stream().map(DemoCatalog.DemoDestination::url))
                .forEach(url -> assertThat(URI.create(url).getHost()).endsWith(".example"));
    }
}
