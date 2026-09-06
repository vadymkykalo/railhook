package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exercises WorkflowEngine's DAG execution directly (topological order, branch
 * routing, filter-driven skips, per-node and whole-execution timeouts, failure
 * propagation) using lightweight fake NodeExecutors rather than a Spring context.
 */
class WorkflowEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowExecutionPersistence persistence;
    private MeterRegistry meterRegistry;
    private final List<WorkflowEngine> enginesToClose = new ArrayList<>();

    @BeforeEach
    void setUp() {
        persistence = mock(WorkflowExecutionPersistence.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        enginesToClose.forEach(WorkflowEngine::destroy);
        enginesToClose.clear();
        WorkflowTriggerService.clearCurrentDepth();
    }

    private WorkflowEngine newEngine(List<NodeExecutor> executors) {
        return newEngine(executors, 600, 30, 60, 60, 30, 16, 5);
    }

    private WorkflowEngine newEngine(List<NodeExecutor> executors, int maxDurationSeconds, int defaultTimeoutSeconds,
                                      int httpTimeoutSeconds, int slackTimeoutSeconds,
                                      int createEventTimeoutSeconds, int poolSize, int shutdownAwaitSeconds) {
        WorkflowEngine engine = new WorkflowEngine(executors, persistence, mapper, meterRegistry,
                maxDurationSeconds, defaultTimeoutSeconds, httpTimeoutSeconds, slackTimeoutSeconds,
                createEventTimeoutSeconds, poolSize, shutdownAwaitSeconds);
        enginesToClose.add(engine);
        return engine;
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Records every invocation and returns a caller-supplied StepResult. */
    private static class RecordingExecutor implements NodeExecutor {
        private final String type;
        private final java.util.function.BiFunction<JsonNode, JsonNode, StepResult> fn;
        final List<JsonNode> receivedInputs = new CopyOnWriteArrayList<>();
        final AtomicInteger invocationCount = new AtomicInteger();

        RecordingExecutor(String type, java.util.function.BiFunction<JsonNode, JsonNode, StepResult> fn) {
            this.type = type;
            this.fn = fn;
        }

        static RecordingExecutor passthrough(String type) {
            return new RecordingExecutor(type, (config, input) -> StepResult.success(input));
        }

        static RecordingExecutor fixed(String type, StepResult result) {
            return new RecordingExecutor(type, (config, input) -> result);
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public StepResult execute(JsonNode nodeConfig, JsonNode input) {
            invocationCount.incrementAndGet();
            receivedInputs.add(input);
            return fn.apply(nodeConfig, input);
        }
    }

    // ─── Empty / trivial workflows ───────────────────────────────────────

    @Test
    void emptyNodesArray_completesImmediatelyWithoutSteps() {
        WorkflowEngine engine = newEngine(List.of());
        UUID executionId = UUID.randomUUID();

        engine.execute(executionId, "{\"nodes\":[],\"edges\":[]}", json("{}"));

        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), isNull(), anyLong());
        verify(persistence, never()).saveStep(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void missingNodesField_completesImmediately() {
        WorkflowEngine engine = newEngine(List.of());
        UUID executionId = UUID.randomUUID();

        engine.execute(executionId, "{\"edges\":[]}", json("{}"));

        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), isNull(), anyLong());
    }

    // ─── Linear chain / piping ────────────────────────────────────────────

    @Test
    void linearChain_pipesEachNodesOutputAsTheNextNodesInput() {
        RecordingExecutor step1 = new RecordingExecutor("step1", (config, input) -> {
            ObjectNode out = ((ObjectNode) input.deepCopy());
            out.put("step1", true);
            return StepResult.success(out);
        });
        RecordingExecutor step2 = RecordingExecutor.passthrough("step2");

        WorkflowEngine engine = newEngine(List.of(step1, step2));
        UUID executionId = UUID.randomUUID();

        String definition = """
                {
                  "nodes": [
                    {"id":"n1","type":"step1","data":{}},
                    {"id":"n2","type":"step2","data":{}}
                  ],
                  "edges": [
                    {"source":"n1","target":"n2"}
                  ]
                }
                """;
        engine.execute(executionId, definition, json("{\"orderId\":\"o-1\"}"));

        assertThat(step1.invocationCount.get()).isEqualTo(1);
        assertThat(step1.receivedInputs.get(0).get("orderId").asText()).isEqualTo("o-1");

        assertThat(step2.invocationCount.get()).isEqualTo(1);
        assertThat(step2.receivedInputs.get(0).get("step1").asBoolean()).isTrue();

        verify(persistence, times(2)).saveStep(eq(executionId), anyString(), anyString(), any(), any(), anyInt());
        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), isNull(), anyLong());
    }

    // ─── Branch routing ─────────────────────────────────────────────────

    private String branchWorkflowDefinition() {
        return """
                {
                  "nodes": [
                    {"id":"trigger","type":"trigger","data":{}},
                    {"id":"branch","type":"branch","data":{"conditions":{"type":"predicate","field":"amount","operator":"GT","value":100,"valueType":"NUMBER"}}},
                    {"id":"onTrue","type":"sinkTrue","data":{}},
                    {"id":"onFalse","type":"sinkFalse","data":{}}
                  ],
                  "edges": [
                    {"source":"trigger","target":"branch"},
                    {"source":"branch","target":"onTrue","sourceHandle":"true"},
                    {"source":"branch","target":"onFalse","sourceHandle":"false"}
                  ]
                }
                """;
    }

    @Test
    void branchNode_matchingCondition_routesOnlyToTrueHandle() {
        RecordingExecutor trigger = RecordingExecutor.passthrough("trigger");
        com.webhook.platform.api.service.workflow.executors.BranchNodeExecutor branch =
                new com.webhook.platform.api.service.workflow.executors.BranchNodeExecutor(mapper);
        RecordingExecutor onTrue = RecordingExecutor.passthrough("sinkTrue");
        RecordingExecutor onFalse = RecordingExecutor.passthrough("sinkFalse");

        WorkflowEngine engine = newEngine(List.of(trigger, branch, onTrue, onFalse));
        UUID executionId = UUID.randomUUID();

        engine.execute(executionId, branchWorkflowDefinition(), json("{\"amount\":200}"));

        assertThat(onTrue.invocationCount.get()).isEqualTo(1);
        assertThat(onFalse.invocationCount.get()).isEqualTo(0);

        ArgumentCaptor<StepResult> resultCaptor = ArgumentCaptor.forClass(StepResult.class);
        verify(persistence, times(4)).saveStep(eq(executionId), anyString(), anyString(), any(), resultCaptor.capture(), anyInt());
        long skippedCount = resultCaptor.getAllValues().stream().filter(r -> r.status() == StepStatus.SKIPPED).count();
        assertThat(skippedCount).isEqualTo(1); // onFalse skipped, branch not taken

        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), isNull(), anyLong());
    }

    @Test
    void branchNode_nonMatchingCondition_routesOnlyToFalseHandle() {
        RecordingExecutor trigger = RecordingExecutor.passthrough("trigger");
        com.webhook.platform.api.service.workflow.executors.BranchNodeExecutor branch =
                new com.webhook.platform.api.service.workflow.executors.BranchNodeExecutor(mapper);
        RecordingExecutor onTrue = RecordingExecutor.passthrough("sinkTrue");
        RecordingExecutor onFalse = RecordingExecutor.passthrough("sinkFalse");

        WorkflowEngine engine = newEngine(List.of(trigger, branch, onTrue, onFalse));
        UUID executionId = UUID.randomUUID();

        engine.execute(executionId, branchWorkflowDefinition(), json("{\"amount\":10}"));

        assertThat(onTrue.invocationCount.get()).isEqualTo(0);
        assertThat(onFalse.invocationCount.get()).isEqualTo(1);
        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), isNull(), anyLong());
    }

    // ─── Filter-driven skip ────────────────────────────────────────────

    @Test
    void filterNode_nonMatchingCondition_skipsDownstreamNodeWithoutInvokingIt() {
        RecordingExecutor trigger = RecordingExecutor.passthrough("trigger");
        com.webhook.platform.api.service.workflow.executors.FilterNodeExecutor filter =
                new com.webhook.platform.api.service.workflow.executors.FilterNodeExecutor(mapper);
        RecordingExecutor downstream = RecordingExecutor.passthrough("downstream");

        WorkflowEngine engine = newEngine(List.of(trigger, filter, downstream));
        UUID executionId = UUID.randomUUID();

        String definition = """
                {
                  "nodes": [
                    {"id":"trigger","type":"trigger","data":{}},
                    {"id":"filter","type":"filter","data":{"conditions":{"type":"predicate","field":"status","operator":"EQ","value":"active","valueType":"STRING"}}},
                    {"id":"downstream","type":"downstream","data":{}}
                  ],
                  "edges": [
                    {"source":"trigger","target":"filter"},
                    {"source":"filter","target":"downstream"}
                  ]
                }
                """;
        engine.execute(executionId, definition, json("{\"status\":\"inactive\"}"));

        assertThat(downstream.invocationCount.get()).isEqualTo(0);

        ArgumentCaptor<String> nodeIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistence, times(3)).saveStep(eq(executionId), nodeIdCaptor.capture(), anyString(), any(), any(), anyInt());
        assertThat(nodeIdCaptor.getAllValues()).contains("trigger", "filter", "downstream");

        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), isNull(), anyLong());
    }

    // ─── Failure propagation ───────────────────────────────────────────

    @Test
    void nodeFailure_stopsExecutionAndNeverRunsDownstreamNodes() {
        RecordingExecutor n1 = RecordingExecutor.passthrough("ok");
        RecordingExecutor n2 = RecordingExecutor.fixed("boom", StepResult.failed("simulated failure"));
        RecordingExecutor n3 = RecordingExecutor.passthrough("neverRuns");

        WorkflowEngine engine = newEngine(List.of(n1, n2, n3));
        UUID executionId = UUID.randomUUID();

        String definition = """
                {
                  "nodes": [
                    {"id":"n1","type":"ok","data":{}},
                    {"id":"n2","type":"boom","data":{}},
                    {"id":"n3","type":"neverRuns","data":{}}
                  ],
                  "edges": [
                    {"source":"n1","target":"n2"},
                    {"source":"n2","target":"n3"}
                  ]
                }
                """;
        engine.execute(executionId, definition, json("{}"));

        assertThat(n1.invocationCount.get()).isEqualTo(1);
        assertThat(n2.invocationCount.get()).isEqualTo(1);
        assertThat(n3.invocationCount.get()).isEqualTo(0);

        verify(persistence, times(2)).saveStep(eq(executionId), anyString(), anyString(), any(), any(), anyInt());
        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.FAILED), eq("simulated failure"), anyLong());
    }

    @Test
    void nodeFailure_errorMessagePropagatesToCompleteExecution() {
        RecordingExecutor n1 = RecordingExecutor.fixed("boom", StepResult.failed("db unreachable"));
        WorkflowEngine engine = newEngine(List.of(n1));
        UUID executionId = UUID.randomUUID();

        engine.execute(executionId, """
                {"nodes":[{"id":"n1","type":"boom","data":{}}],"edges":[]}
                """, json("{}"));

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.FAILED), errorCaptor.capture(), anyLong());
        assertThat(errorCaptor.getValue()).isEqualTo("db unreachable");
    }

    // ─── Unknown node type ──────────────────────────────────────────────

    @Test
    void unknownNodeType_marksNodeFailedButWorkflowStillCompletes() {
        RecordingExecutor known = RecordingExecutor.passthrough("known");
        WorkflowEngine engine = newEngine(List.of(known));
        UUID executionId = UUID.randomUUID();

        String definition = """
                {
                  "nodes": [
                    {"id":"n1","type":"totallyUnregistered","data":{}},
                    {"id":"n2","type":"known","data":{}}
                  ],
                  "edges": [
                    {"source":"n1","target":"n2"}
                  ]
                }
                """;
        engine.execute(executionId, definition, json("{}"));

        // n2 depends only on the unknown node, which gets marked skipped internally,
        // so n2 is never invoked either — but the *workflow itself* still completes
        // rather than failing, because unregistered types don't trigger the FAILED/return path.
        assertThat(known.invocationCount.get()).isEqualTo(0);

        ArgumentCaptor<StepResult> resultCaptor = ArgumentCaptor.forClass(StepResult.class);
        verify(persistence, times(2)).saveStep(eq(executionId), anyString(), anyString(), any(), resultCaptor.capture(), anyInt());
        assertThat(resultCaptor.getAllValues().get(0).status()).isEqualTo(StepStatus.FAILED);
        assertThat(resultCaptor.getAllValues().get(0).errorMessage()).contains("Unknown node type");

        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.COMPLETED), isNull(), anyLong());
    }

    // ─── Depth propagation (recursion guard support) ───────────────────

    @Test
    void currentDepth_isCapturedFromCallingThread_andVisibleInsideNodeExecutorThread() {
        AtomicInteger observedDepth = new AtomicInteger(-1);
        RecordingExecutor depthProbe = new RecordingExecutor("depthProbe", (config, input) -> {
            observedDepth.set(WorkflowTriggerService.getCurrentDepth());
            return StepResult.success(mapper.createObjectNode());
        });

        WorkflowEngine engine = newEngine(List.of(depthProbe));
        UUID executionId = UUID.randomUUID();

        WorkflowTriggerService.setCurrentDepth(2);
        try {
            engine.execute(executionId, """
                    {"nodes":[{"id":"n1","type":"depthProbe","data":{}}],"edges":[]}
                    """, json("{}"));
        } finally {
            WorkflowTriggerService.clearCurrentDepth();
        }

        assertThat(observedDepth.get()).isEqualTo(2);
    }

    @Test
    void currentDepth_defaultsToZero_whenNeverSetOnCallingThread() {
        AtomicInteger observedDepth = new AtomicInteger(-1);
        RecordingExecutor depthProbe = new RecordingExecutor("depthProbe", (config, input) -> {
            observedDepth.set(WorkflowTriggerService.getCurrentDepth());
            return StepResult.success(mapper.createObjectNode());
        });

        WorkflowEngine engine = newEngine(List.of(depthProbe));
        UUID executionId = UUID.randomUUID();

        engine.execute(executionId, """
                {"nodes":[{"id":"n1","type":"depthProbe","data":{}}],"edges":[]}
                """, json("{}"));

        assertThat(observedDepth.get()).isEqualTo(0);
    }

    // ─── Per-node timeout ───────────────────────────────────────────────

    @Test
    void perNodeTimeout_exceeded_returnsFailedWithoutHangingTheEngine() {
        RecordingExecutor slowNode = new RecordingExecutor("slow", (config, input) -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StepResult.success(mapper.createObjectNode());
        });

        // defaultTimeoutSeconds=1 so "slow" (not http/slack/delay/createEvent) times out fast.
        WorkflowEngine engine = newEngine(List.of(slowNode), 600, 1, 60, 60, 30, 4, 2);
        UUID executionId = UUID.randomUUID();

        long start = System.currentTimeMillis();
        engine.execute(executionId, """
                {"nodes":[{"id":"n1","type":"slow","data":{}}],"edges":[]}
                """, json("{}"));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(4000);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.FAILED), errorCaptor.capture(), anyLong());
        assertThat(errorCaptor.getValue()).contains("Node timeout");
    }

    // ─── Whole-execution timeout ────────────────────────────────────────

    @Test
    void globalExecutionTimeout_stopsBeforeStartingTheNextNode() {
        RecordingExecutor slowButUnderNodeTimeout = new RecordingExecutor("slow", (config, input) -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StepResult.success(input);
        });
        RecordingExecutor neverRuns = RecordingExecutor.passthrough("neverRuns");

        // maxDurationSeconds=1 (1000ms) but defaultTimeoutSeconds=5 so node n1 itself
        // is allowed to finish its 1.2s sleep — the *global* check trips before n2 starts.
        WorkflowEngine engine = newEngine(List.of(slowButUnderNodeTimeout, neverRuns), 1, 5, 60, 60, 30, 4, 2);
        UUID executionId = UUID.randomUUID();

        String definition = """
                {
                  "nodes": [
                    {"id":"n1","type":"slow","data":{}},
                    {"id":"n2","type":"neverRuns","data":{}}
                  ],
                  "edges": [
                    {"source":"n1","target":"n2"}
                  ]
                }
                """;
        engine.execute(executionId, definition, json("{}"));

        assertThat(neverRuns.invocationCount.get()).isEqualTo(0);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.FAILED), errorCaptor.capture(), anyLong());
        assertThat(errorCaptor.getValue()).contains("timeout");
    }

    // ─── Malformed definition ───────────────────────────────────────────

    @Test
    void malformedDefinitionJson_failsExecutionGracefully() {
        WorkflowEngine engine = newEngine(List.of());
        UUID executionId = UUID.randomUUID();

        engine.execute(executionId, "{not valid json", json("{}"));

        verify(persistence).completeExecution(eq(executionId), eq(ExecutionStatus.FAILED), anyString(), anyLong());
    }
}
