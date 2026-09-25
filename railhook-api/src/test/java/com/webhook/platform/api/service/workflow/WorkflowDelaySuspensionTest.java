package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.service.workflow.executors.DelayNodeExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Sleeping delay nodes took the whole shared workflow pool, so no organization's workflows ran.
@DisplayName("WorkflowEngine — a delay suspends the execution instead of occupying a thread")
class WorkflowDelaySuspensionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<WorkflowEngine> enginesToClose = new ArrayList<>();

    private WorkflowExecutionPersistence persistence;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        persistence = mock(WorkflowExecutionPersistence.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        enginesToClose.forEach(WorkflowEngine::destroy);
        enginesToClose.clear();
    }

    @Test
    @DisplayName("a delay returns the thread within milliseconds rather than sleeping")
    void delayDoesNotBlockTheThread() {
        UUID executionId = UUID.randomUUID();
        WorkflowEngine engine = newEngine(List.of(new DelayNodeExecutor(), counting("noop", new AtomicInteger())));

        long before = System.currentTimeMillis();
        engine.execute(executionId, definition("""
                {"nodes":[{"id":"a","type":"delay","data":{"delaySeconds":300}}],"edges":[]}
                """), mapper.createObjectNode());
        long elapsed = System.currentTimeMillis() - before;

        assertThat(elapsed)
                .as("the call must return immediately; the waiting happens in the database")
                .isLessThan(2_000);
        verify(persistence).suspendExecution(eq(executionId), any(Instant.class), any(), anyLong());
        verify(persistence, never()).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), any(), anyLong());
    }

    @Test
    @DisplayName("resuming continues after the delay and does not re-run what already ran")
    void resumeDoesNotRepeatCompletedNodes() {
        AtomicInteger beforeRuns = new AtomicInteger();
        AtomicInteger afterRuns = new AtomicInteger();
        UUID executionId = UUID.randomUUID();
        String definition = definition("""
                {"nodes":[{"id":"a","type":"before","data":{}},
                          {"id":"b","type":"delay","data":{"delaySeconds":60}},
                          {"id":"c","type":"after","data":{}}],
                 "edges":[{"source":"a","target":"b"},{"source":"b","target":"c"}]}
                """);
        WorkflowEngine engine = newEngine(List.of(
                new DelayNodeExecutor(), counting("before", beforeRuns), counting("after", afterRuns)));

        engine.execute(executionId, definition, mapper.createObjectNode());
        assertThat(beforeRuns.get()).isEqualTo(1);
        assertThat(afterRuns.get()).isZero();

        engine.resume(executionId, definition, mapper.createObjectNode(), stateResumingAt("c"), 0L);

        // A re-run prefix would post an http node or emit a createEvent node twice.
        assertThat(beforeRuns.get()).as("already done, must not run again").isEqualTo(1);
        assertThat(afterRuns.get()).as("this is what the execution was waiting to do").isEqualTo(1);
    }

    @Test
    @DisplayName("time spent suspended is not charged against the execution budget")
    void suspendedTimeDoesNotCountTowardsTheTimeout() {
        AtomicInteger afterRuns = new AtomicInteger();
        UUID executionId = UUID.randomUUID();
        String definition = definition("""
                {"nodes":[{"id":"b","type":"delay","data":{"delaySeconds":60}},
                          {"id":"c","type":"after","data":{}}],
                 "edges":[{"source":"b","target":"c"}]}
                """);
        WorkflowEngine engine = newEngine(
                List.of(new DelayNodeExecutor(), counting("after", afterRuns)), 1);

        engine.execute(executionId, definition, mapper.createObjectNode());
        engine.resume(executionId, definition, mapper.createObjectNode(),
                stateResumingAt("c"), 0L);

        assertThat(afterRuns.get()).isEqualTo(1);
    }

    private JsonNode stateResumingAt(String nodeId) {
        var state = mapper.createObjectNode();
        state.put("resumeFrom", nodeId);
        state.set("outputs", mapper.createObjectNode());
        state.set("skipped", mapper.createArrayNode());
        return state;
    }

    private String definition(String raw) {
        return raw.strip();
    }

    private NodeExecutor counting(String type, AtomicInteger counter) {
        return new NodeExecutor() {
            @Override public String getType() { return type; }
            @Override public StepResult execute(JsonNode nodeConfig, JsonNode input) {
                counter.incrementAndGet();
                return StepResult.success(mapper.createObjectNode());
            }
        };
    }

    private WorkflowEngine newEngine(List<NodeExecutor> executors) {
        return newEngine(executors, 600);
    }

    private WorkflowEngine newEngine(List<NodeExecutor> executors, int maxDurationSeconds) {
        WorkflowEngine engine = new WorkflowEngine(executors, persistence, mapper, meterRegistry,
                maxDurationSeconds, 30, 60, 60, 30, 16, 5);
        enginesToClose.add(engine);
        return engine;
    }
}
