package com.webhook.platform.api.service.demo;

import com.webhook.platform.api.service.demo.DemoHistory.AttemptRow;
import com.webhook.platform.api.service.demo.DemoHistory.DeliveryRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoWorkflow;
import com.webhook.platform.api.service.demo.DemoHistory.ForwardRow;
import com.webhook.platform.api.service.demo.DemoHistory.WorkflowExecutionRow;
import com.webhook.platform.api.service.demo.DemoHistory.WorkflowStepRow;
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

// None of the demo history may be work: the worker must never find a demo row it would attempt.
class DemoHistoryTest {

    private static final Instant NOW = Instant.parse("2026-09-19T14:30:00Z");

    private final DemoHistory history = DemoHistory.generate(NOW);

    @Test
    void theSameMomentGivesTheSameHistory() {
        DemoHistory again = DemoHistory.generate(NOW);

        assertThat(again.events).isEqualTo(history.events);
        assertThat(again.deliveries).isEqualTo(history.deliveries);
        assertThat(again.attempts).isEqualTo(history.attempts);
        assertThat(again.incomingEvents).isEqualTo(history.incomingEvents);
        assertThat(again.forwards).isEqualTo(history.forwards);
        assertThat(again.workflowExecutions).isEqualTo(history.workflowExecutions);
        assertThat(again.workflowSteps).isEqualTo(history.workflowSteps);
    }

    @Test
    void everyDeliveryAndForwardIsFinished() {
        assertThat(history.deliveries).extracting(DeliveryRow::status).containsOnly("SUCCESS", "DLQ");
        assertThat(history.forwards).extracting(ForwardRow::status).containsOnly("SUCCESS", "FAILED");
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
                history.forwards.stream().map(ForwardRow::finishedAt),
                history.workflowExecutions.stream().map(WorkflowExecutionRow::completedAt),
                history.workflowSteps.stream().map(WorkflowStepRow::completedAt)
        ).flatMap(s -> s).forEach(at -> assertThat(at).isBetween(start, settled));
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
                history.forwards.stream().map(ForwardRow::id),
                history.workflowExecutions.stream().map(WorkflowExecutionRow::id),
                history.workflowSteps.stream().map(WorkflowStepRow::id)
        ).flatMap(s -> s).collect(Collectors.toSet());
        int rows = history.events.size() + history.deliveries.size() + history.attempts.size()
                + history.incomingEvents.size() + history.forwards.size()
                + history.workflowExecutions.size() + history.workflowSteps.size();
        assertThat(ids).hasSize(rows);
    }

    @Test
    void everyTargetIsOnAReservedDocumentationHost() {
        Stream.concat(DemoCatalog.ENDPOINTS.stream().map(DemoCatalog.DemoEndpoint::url),
                        DemoCatalog.DESTINATIONS.stream().map(DemoCatalog.DemoDestination::url))
                .forEach(url -> assertThat(URI.create(url).getHost()).endsWith(".example"));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyWorkflowRunIsFinished() {
        // WAITING and RUNNING would be picked up and run for real by the resume and recovery jobs.
        assertThat(history.workflowExecutions).extracting(WorkflowExecutionRow::status)
                .containsOnly("COMPLETED", "FAILED");
        assertThat(history.workflowExecutions).allSatisfy(run -> {
            assertThat(run.completedAt()).isAfterOrEqualTo(run.startedAt());
            assertThat(run.triggerEventId()).isNotNull();
        });
        assertThat(history.workflowSteps).extracting(WorkflowStepRow::status)
                .doesNotContain("PENDING", "RUNNING");
    }

    @Test
    void aDeliveryStepPointsAtADeliveryThatExists() {
        Set<String> deliveryIds = history.deliveries.stream().map(d -> d.id().toString()).collect(Collectors.toSet());
        Set<String> eventIds = history.events.stream().map(e -> e.id().toString()).collect(Collectors.toSet());
        List<WorkflowStepRow> delivered = history.workflowSteps.stream()
                .filter(s -> s.nodeType().equals("delivery") && s.status().equals("SUCCESS")).toList();

        assertThat(delivered).isNotEmpty();
        for (WorkflowStepRow step : delivered) {
            JsonNode output = json(step.outputData());
            assertThat(deliveryIds).contains(output.get("deliveryId").asText());
            assertThat(eventIds).contains(output.get("eventId").asText());
        }
        Set<String> triggerEvents = history.workflowExecutions.stream().map(r -> r.triggerEventId().toString())
                .collect(Collectors.toSet());
        assertThat(eventIds).containsAll(triggerEvents);
    }

    @Test
    void everyWorkflowDeliversOnlyToTheDemosOwnEndpoints() {
        Set<String> endpoints = DemoCatalog.ENDPOINTS.stream().map(e -> e.id().toString()).collect(Collectors.toSet());
        for (DemoWorkflow workflow : DemoCatalog.WORKFLOWS) {
            JsonNode definition = json(workflow.definition());
            definition.get("nodes").forEach(node -> {
                assertThat(node.get("type").asText()).isIn("webhookTrigger", "filter", "branch", "transform",
                        "delay", "delivery");
                assertThat(node.has("position")).isTrue();
                if (node.get("type").asText().equals("delivery")) {
                    assertThat(endpoints).contains(node.get("data").get("endpointId").asText());
                }
            });
        }
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
