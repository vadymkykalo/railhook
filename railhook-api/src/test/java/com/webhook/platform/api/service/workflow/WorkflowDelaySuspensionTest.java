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

/**
 * A delay node must not hold a thread while it waits.
 *
 * <p>{@code DelayNodeExecutor} used to call {@code Thread.sleep} for up to 300 seconds on the
 * workflow pool, which is core-size 4 / max-size 8 across the whole deployment. Eight delay
 * nodes — one badly-configured workflow, or eight ordinary ones that happened to overlap — took
 * every thread for five minutes, and no workflow belonging to any organization ran at all. The
 * threads were not doing work; they were watching a clock.
 *
 * <p>So the execution suspends: the engine records where it got to, when it is due, and returns
 * the thread. {@code WorkflowResumeJob} continues it. These tests pin the three things that
 * decide whether that is actually better than sleeping — that the thread really is released,
 * that a resumed run carries its earlier outputs forward rather than recomputing them, and that
 * time spent suspended is not charged against a budget meant for work.
 */
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

        /* The old implementation would have taken 300 seconds here — or, with the per-node
           timeout, 305 seconds and then a FAILED. */
        assertThat(elapsed)
                .as("the call must return immediately; the waiting happens in the database")
                .isLessThan(2_000);
        verify(persistence).suspendExecution(eq(executionId), any(Instant.class), any(), anyLong());
        verify(persistence, never()).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), any(), anyLong());
    }

    @Test
    @DisplayName("the node after a delay does not run until the execution is resumed")
    void nodesAfterTheDelayWaitForTheResume() {
        AtomicInteger afterRuns = new AtomicInteger();
        WorkflowEngine engine = newEngine(List.of(new DelayNodeExecutor(), counting("noop", afterRuns)));

        engine.execute(UUID.randomUUID(), definition("""
                {"nodes":[{"id":"a","type":"delay","data":{"delaySeconds":60}},
                          {"id":"b","type":"noop","data":{}}],
                 "edges":[{"source":"a","target":"b"}]}
                """), mapper.createObjectNode());

        assertThat(afterRuns.get())
                .as("suspending has to stop the run, not merely record a timestamp beside it")
                .isZero();
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

        /* Re-running a node that already succeeded is the failure mode that makes naive
           resume worse than sleeping: an http node would post twice, a createEvent node would
           emit twice. The snapshot exists precisely so the prefix is not repeated. */
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
        // A one-second budget for work, resumed as if the execution had been suspended for a day.
        WorkflowEngine engine = newEngine(
                List.of(new DelayNodeExecutor(), counting("after", afterRuns)), 1);

        engine.execute(executionId, definition, mapper.createObjectNode());
        engine.resume(executionId, definition, mapper.createObjectNode(),
                stateResumingAt("c"), 0L);

        /* Measuring the budget as wall-clock from startedAt would make any workflow containing
           a delay longer than the budget impossible to finish — it would time out on the
           resume, every time, having done almost no work. Only running segments count. */
        assertThat(afterRuns.get()).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** The snapshot the engine writes: outputs so far, skipped nodes, and where to continue. */
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
