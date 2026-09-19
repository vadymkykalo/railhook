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
import java.util.HashSet;
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
        assertThat(again.workflowExecutions).isEqualTo(history.workflowExecutions);
        assertThat(again.workflowSteps).isEqualTo(history.workflowSteps);
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
                history.forwards.stream().map(ForwardRow::finishedAt),
                history.workflowExecutions.stream().map(WorkflowExecutionRow::completedAt),
                history.workflowSteps.stream().map(WorkflowStepRow::completedAt)
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

    // ── Workflows ──────────────────────────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyWorkflowRunIsFinished() {
        // WAITING is picked up by the resume job and RUNNING by the recovery job: neither may
        // exist in the demo, or the engine would go on to run a seeded execution for real.
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
    void everyWorkflowRunsAndEachShowsWhatItIsFor() {
        Map<UUID, List<WorkflowExecutionRow>> runs = history.workflowExecutions.stream()
                .collect(Collectors.groupingBy(WorkflowExecutionRow::workflowId));
        for (DemoWorkflow workflow : DemoCatalog.WORKFLOWS) {
            assertThat(runs.get(workflow.id())).as(workflow.name()).hasSizeGreaterThan(2);
        }

        // The branch sends some orders each way.
        List<WorkflowStepRow> branches = stepsOf(DemoCatalog.HIGH_VALUE_ORDERS, "branch");
        assertThat(branches).extracting(s -> json(s.outputData()).get("_branchHandle").asText())
                .contains("true", "false");
        // A filter both lets runs through and stops them.
        List<WorkflowStepRow> filters = stepsOf(DemoCatalog.SHIPMENT_REVIEWS, "filter");
        assertThat(filters).extracting(WorkflowStepRow::status).contains("SUCCESS", "SKIPPED");
        // A delay suspends the run, which then carries on to its delivery.
        assertThat(stepsOf(DemoCatalog.CARD_DECLINES, "delay")).extracting(WorkflowStepRow::status).contains("WAITING");
        assertThat(stepsOf(DemoCatalog.CARD_DECLINES, "delivery")).extracting(WorkflowStepRow::status).contains("SUCCESS");
        // And no node ever failed on its own configuration.
        assertThat(history.workflowSteps).filteredOn(s -> s.status().equals("FAILED"))
                .allSatisfy(s -> assertThat(s.nodeType()).isEqualTo("delivery"));
    }

    @Test
    void aWorkflowRunTakesOnlyItsOwnNodesInTheOrderTheEdgesAllow() {
        Map<UUID, List<WorkflowStepRow>> stepsByRun = history.workflowSteps.stream()
                .collect(Collectors.groupingBy(WorkflowStepRow::executionId));
        for (WorkflowExecutionRow run : history.workflowExecutions) {
            DemoWorkflow workflow = DemoCatalog.WORKFLOWS.stream().filter(w -> w.id().equals(run.workflowId()))
                    .findFirst().orElseThrow();
            JsonNode definition = json(workflow.definition());
            Set<String> nodeIds = new HashSet<>();
            definition.get("nodes").forEach(n -> nodeIds.add(n.get("id").asText()));
            List<WorkflowStepRow> steps = stepsByRun.get(run.id());
            assertThat(steps).isNotEmpty();
            Set<String> seen = new HashSet<>();
            for (WorkflowStepRow step : steps) {
                assertThat(nodeIds).contains(step.nodeId());
                for (JsonNode edge : definition.get("edges")) {
                    if (edge.get("target").asText().equals(step.nodeId())) {
                        assertThat(seen).as("a node runs after its parents").contains(edge.get("source").asText());
                    }
                }
                seen.add(step.nodeId());
            }
            // Steps are listed by when they were written, so no two may share a moment.
            assertThat(steps).extracting(WorkflowStepRow::createdAt).doesNotHaveDuplicates().isSorted();
        }
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

    private List<WorkflowStepRow> stepsOf(DemoWorkflow workflow, String nodeType) {
        Set<UUID> runs = history.workflowExecutions.stream().filter(r -> r.workflowId().equals(workflow.id()))
                .map(WorkflowExecutionRow::id).collect(Collectors.toSet());
        return history.workflowSteps.stream()
                .filter(s -> runs.contains(s.executionId()) && s.nodeType().equals(nodeType)).toList();
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
