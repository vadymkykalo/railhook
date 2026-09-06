package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delay node asks to be woken; it does not wait.
 *
 * <p>These tests previously asserted that {@code execute} slept for roughly the configured
 * number of seconds — which was the behaviour, and was the bug. The workflow pool is core-size 4
 * / max-size 8 for the whole deployment and a delay may be 300 seconds, so eight delay nodes
 * took every thread and no workflow belonging to any organization ran. The node now returns a
 * due time and the engine suspends the execution, so the clamping the old tests checked is
 * still worth checking — just not by timing it.
 */
@DisplayName("DelayNodeExecutor — returns a due time rather than occupying a thread")
class DelayNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DelayNodeExecutor executor = new DelayNodeExecutor();

    @Test
    void getType_returnsDelay() {
        assertThat(executor.getType()).isEqualTo("delay");
    }

    @Test
    @DisplayName("returns immediately, asking to resume after the configured delay")
    void returnsWaitingWithoutSleeping() throws Exception {
        JsonNode config = mapper.readTree("{\"delaySeconds\":300}");
        JsonNode input = mapper.readTree("{\"a\":1}");

        Instant before = Instant.now();
        long start = System.currentTimeMillis();
        StepResult result = executor.execute(config, input);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("300 seconds of delay must cost no thread time at all")
                .isLessThan(500);
        assertThat(result.status()).isEqualTo(StepStatus.WAITING);
        assertThat(result.resumeAt())
                .isBetween(before.plusSeconds(299), before.plusSeconds(302));
        assertThat(result.output())
                .as("a suspension is transparent to whatever comes next")
                .isEqualTo(input);
    }

    @Test
    @DisplayName("no configured delay uses the default")
    void defaultsToFiveSeconds() throws Exception {
        Instant before = Instant.now();
        StepResult result = executor.execute(mapper.readTree("{}"), mapper.readTree("{}"));

        assertThat(result.resumeAt()).isBetween(before.plusSeconds(4), before.plusSeconds(7));
    }

    @Test
    @DisplayName("zero and negative are clamped to one second, not to 'now'")
    void nonPositiveDelayIsClampedToOneSecond() throws Exception {
        for (String raw : new String[]{"{\"delaySeconds\":0}", "{\"delaySeconds\":-30}"}) {
            Instant before = Instant.now();
            StepResult result = executor.execute(mapper.readTree(raw), mapper.readTree("{}"));

            /* A resumeAt in the past would be resumed on the very next tick, which is close
               enough to correct — but a delay node that resolves to no delay reads as a bug in
               the workflow rather than in the configuration, and one second is the smallest
               honest answer. */
            assertThat(result.resumeAt())
                    .as(raw)
                    .isAfterOrEqualTo(before)
                    .isBefore(before.plusSeconds(3));
        }
    }

    @Test
    @DisplayName("a delay beyond the maximum is capped rather than rejected")
    void oversizedDelayIsCapped() throws Exception {
        Instant before = Instant.now();
        StepResult result = executor.execute(
                mapper.readTree("{\"delaySeconds\":99999}"), mapper.readTree("{}"));

        assertThat(result.resumeAt()).isBefore(before.plusSeconds(302));
    }
}
