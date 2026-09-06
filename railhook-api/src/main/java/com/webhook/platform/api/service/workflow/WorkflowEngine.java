package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.tenancy.TenantPropagatingTaskDecorator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core workflow execution engine.
 * Executes a DAG of nodes in topological order, piping each node's output
 * as the next node's input. Supports branching (fan-out) and filtering (skip downstream).
 *
 * Reliability:
 * - Overall execution timeout (configurable, default 10 minutes)
 * - Per-node timeout (configurable per type)
 * - Thread interrupt awareness for graceful shutdown
 * - Transactional completion status writes
 * - Graceful shutdown: waits for in-flight nodes, then interrupts
 */
@Service
@Slf4j
public class WorkflowEngine implements DisposableBean {

    private final long maxExecutionMs;
    private final Map<String, Long> nodeTimeouts;
    private final long defaultNodeTimeoutMs;
    private final int shutdownAwaitSeconds;

    /**
     * Bounded thread pool for per-node timeout enforcement, wrapped so a node runs in the tenant
     * of the workflow that scheduled it — node executors read endpoints and write deliveries, all
     * {@code @TenantId} entities, and this pool is built here rather than in {@code AsyncConfig},
     * so nothing else would give it a scope.
     */
    private final ExecutorService nodeTimeoutExecutor;

    /** The same pool, undecorated: {@code nodeTimeoutExecutor} hides its saturation counters. */
    private final ThreadPoolExecutor nodeTimeoutPool;

    private final Map<String, NodeExecutor> executors;
    private final WorkflowExecutionPersistence persistence;
    private final ObjectMapper objectMapper;
    private final Counter rejectedNodeCounter;

    public WorkflowEngine(
            List<NodeExecutor> nodeExecutors,
            WorkflowExecutionPersistence persistence,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${workflow.execution.max-duration-seconds:600}") int maxDurationSeconds,
            @Value("${workflow.node-timeout.default-seconds:30}") int defaultTimeoutSeconds,
            @Value("${workflow.node-timeout.http-seconds:60}") int httpTimeoutSeconds,
            @Value("${workflow.node-timeout.slack-seconds:60}") int slackTimeoutSeconds,
            @Value("${workflow.node-timeout.create-event-seconds:30}") int createEventTimeoutSeconds,
            @Value("${workflow.node-timeout.pool-size:16}") int nodeTimeoutPoolSize,
            @Value("${workflow.shutdown.await-termination-seconds:30}") int shutdownAwaitSeconds) {
        this.executors = nodeExecutors.stream()
                .collect(Collectors.toMap(NodeExecutor::getType, Function.identity()));
        this.persistence = persistence;
        this.objectMapper = objectMapper;
        this.rejectedNodeCounter = Counter.builder("workflow_node_executions_rejected_total")
                .description("Node executions rejected due to pool saturation")
                .register(meterRegistry);
        this.maxExecutionMs = maxDurationSeconds * 1000L;
        this.defaultNodeTimeoutMs = defaultTimeoutSeconds * 1000L;
        this.shutdownAwaitSeconds = shutdownAwaitSeconds;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                nodeTimeoutPoolSize, nodeTimeoutPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(nodeTimeoutPoolSize * 4),
                r -> {
                    Thread t = new Thread(r, "wf-node-timeout");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        this.nodeTimeoutPool = pool;
        this.nodeTimeoutExecutor = TenantPropagatingTaskDecorator.wrap(pool);
        // No "delay" entry any more: a delay node returns a due time in microseconds instead
        // of sleeping, so the 305-second allowance it needed — and the config knob that set it —
        // measured something that no longer happens.
        this.nodeTimeouts = Map.of(
                "http", httpTimeoutSeconds * 1000L,
                "slack", slackTimeoutSeconds * 1000L,
                "createEvent", createEventTimeoutSeconds * 1000L
        );
        log.info("WorkflowEngine initialized with {} executors: {}, maxExecution={}s, shutdown={}s",
                executors.size(), executors.keySet(), maxDurationSeconds, shutdownAwaitSeconds);
    }

    @Override
    public void destroy() {
        log.info("WorkflowEngine shutting down — waiting for in-flight node executions...");
        nodeTimeoutExecutor.shutdown();
        try {
            if (!nodeTimeoutExecutor.awaitTermination(shutdownAwaitSeconds, TimeUnit.SECONDS)) {
                log.warn("Force-interrupting {} remaining node executions", nodeTimeoutExecutor.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            nodeTimeoutExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("WorkflowEngine shutdown complete");
    }

    /**
     * Execute a workflow definition for a given execution.
     *
     * @param executionId  the persisted WorkflowExecution ID
     * @param definitionJson the workflow definition JSON string
     * @param triggerData  the trigger event payload
     */
    public void execute(UUID executionId, String definitionJson, JsonNode triggerData) {
        run(executionId, definitionJson, triggerData, null, 0L);
    }

    /**
     * Continues an execution that suspended at a delay node.
     *
     * <p>Called by {@code WorkflowResumeJob} once {@code resumeAt} has passed. {@code state} is
     * the snapshot {@link #execute} wrote — the outputs produced before the delay, the nodes
     * already skipped, and the node to continue from. Replaying the prefix instead of restoring
     * it would be the failure mode that makes a naive resume worse than sleeping: an http node
     * would post twice and a createEvent node would emit twice.
     *
     * @param workingMsSoFar milliseconds of actual node execution before the suspension, so the
     *                       global timeout stays a budget for work rather than wall-clock
     */
    public void resume(UUID executionId, String definitionJson, JsonNode triggerData,
                       JsonNode state, long workingMsSoFar) {
        run(executionId, definitionJson, triggerData, state, workingMsSoFar);
    }

    private void run(UUID executionId, String definitionJson, JsonNode triggerData,
                     JsonNode resumeState, long workingMsSoFar) {
        long startTime = System.currentTimeMillis();
        try {
            JsonNode def = objectMapper.readTree(definitionJson);
            JsonNode nodesArray = def.get("nodes");
            JsonNode edgesArray = def.get("edges");

            if (nodesArray == null || !nodesArray.isArray() || nodesArray.isEmpty()) {
                persistence.completeExecution(executionId, ExecutionStatus.COMPLETED, null, startTime);
                return;
            }

            // Parse nodes and edges
            Map<String, JsonNode> nodesById = new LinkedHashMap<>();
            for (JsonNode node : nodesArray) {
                nodesById.put(node.get("id").asText(), node);
            }

            Map<String, List<String>> incomingEdges = new HashMap<>(); // targetId → [sourceIds]
            Map<String, List<EdgeInfo>> outgoingEdges = new HashMap<>(); // sourceId → [EdgeInfo]
            if (edgesArray != null && edgesArray.isArray()) {
                for (JsonNode edge : edgesArray) {
                    String source = edge.get("source").asText();
                    String target = edge.get("target").asText();
                    String sourceHandle = edge.has("sourceHandle") ? edge.get("sourceHandle").asText() : null;
                    incomingEdges.computeIfAbsent(target, k -> new ArrayList<>()).add(source);
                    outgoingEdges.computeIfAbsent(source, k -> new ArrayList<>()).add(new EdgeInfo(target, sourceHandle));
                }
            }

            // Topological sort
            List<String> order = topologicalSort(nodesById.keySet(), incomingEdges);

            // Build reverse lookup: for each (source→target) edge, store the sourceHandle
            // so we can check branch routing
            Map<String, Map<String, String>> edgeSourceHandles = new HashMap<>(); // target → (source → sourceHandle)
            if (edgesArray != null && edgesArray.isArray()) {
                for (JsonNode edge : edgesArray) {
                    String source = edge.get("source").asText();
                    String target = edge.get("target").asText();
                    String sh = edge.has("sourceHandle") && !edge.get("sourceHandle").isNull()
                            ? edge.get("sourceHandle").asText() : null;
                    edgeSourceHandles.computeIfAbsent(target, k -> new HashMap<>()).put(source, sh);
                }
            }

            // Execute nodes in order, from the beginning or from where a suspension left off.
            Map<String, JsonNode> outputs = new HashMap<>();
            Set<String> skippedNodes = new HashSet<>();
            String resumeFrom = null;
            if (resumeState != null) {
                JsonNode savedOutputs = resumeState.get("outputs");
                if (savedOutputs != null) {
                    savedOutputs.fields().forEachRemaining(e -> outputs.put(e.getKey(), e.getValue()));
                }
                JsonNode savedSkipped = resumeState.get("skipped");
                if (savedSkipped != null) {
                    savedSkipped.forEach(n -> skippedNodes.add(n.asText()));
                }
                resumeFrom = resumeState.hasNonNull("resumeFrom")
                        ? resumeState.get("resumeFrom").asText() : null;
            }
            boolean skippingToResumePoint = resumeFrom != null;

            for (String nodeId : order) {
                // Everything before the resume point already ran, and its output is restored
                // above. Re-running it would repeat side effects the execution already had.
                if (skippingToResumePoint) {
                    if (nodeId.equals(resumeFrom)) {
                        skippingToResumePoint = false;
                    } else {
                        continue;
                    }
                }

                // ── Global timeout check ─────────────────────────
                long elapsed = workingMsSoFar + (System.currentTimeMillis() - startTime);
                if (elapsed > maxExecutionMs) {
                    String msg = String.format("Workflow execution timeout after %ds (max %ds)",
                            elapsed / 1000, maxExecutionMs / 1000);
                    log.warn("Execution {} timed out: {}", executionId, msg);
                    persistence.completeExecution(executionId, ExecutionStatus.FAILED, msg, startTime);
                    return;
                }

                // ── Thread interrupt check (graceful shutdown) ───
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Execution {} interrupted (shutdown?)", executionId);
                    persistence.completeExecution(executionId, ExecutionStatus.CANCELLED,
                            "Execution interrupted (server shutdown)", startTime);
                    return;
                }

                JsonNode nodeDef = nodesById.get(nodeId);
                String nodeType = nodeDef.get("type").asText();
                JsonNode nodeData = nodeDef.has("data") ? nodeDef.get("data") : objectMapper.createObjectNode();

                // Check if all parents are skipped or branch-blocked → skip this node too
                List<String> parents = incomingEdges.getOrDefault(nodeId, List.of());
                boolean allParentsBlocked = !parents.isEmpty() && parents.stream().allMatch(parentId -> {
                    if (skippedNodes.contains(parentId)) return true;
                    // Check branch routing: if parent output has _branchHandle,
                    // only allow this edge if its sourceHandle matches
                    JsonNode parentOutput = outputs.get(parentId);
                    if (parentOutput != null && parentOutput.has("_branchHandle")) {
                        String branchHandle = parentOutput.get("_branchHandle").asText();
                        Map<String, String> handles = edgeSourceHandles.getOrDefault(nodeId, Map.of());
                        String edgeHandle = handles.get(parentId);
                        return edgeHandle != null && !edgeHandle.equals(branchHandle);
                    }
                    return false;
                });

                if (allParentsBlocked) {
                    skippedNodes.add(nodeId);
                    persistence.saveStep(executionId, nodeId, nodeType, null, StepResult.skipped("Parent nodes skipped or branch not taken"), 0);
                    continue;
                }

                // Gather input from parent outputs (first non-null, non-branch-blocked parent)
                JsonNode input;
                if (parents.isEmpty()) {
                    input = triggerData; // root node gets trigger data
                } else {
                    input = parents.stream()
                            .filter(p -> !skippedNodes.contains(p))
                            .filter(p -> {
                                JsonNode po = outputs.get(p);
                                if (po != null && po.has("_branchHandle")) {
                                    String bh = po.get("_branchHandle").asText();
                                    Map<String, String> handles = edgeSourceHandles.getOrDefault(nodeId, Map.of());
                                    String eh = handles.get(p);
                                    return eh == null || eh.equals(bh);
                                }
                                return true;
                            })
                            .map(outputs::get)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(triggerData);
                }

                // Find executor
                NodeExecutor executor = executors.get(nodeType);
                if (executor == null) {
                    log.warn("No executor for node type '{}', skipping node {}", nodeType, nodeId);
                    skippedNodes.add(nodeId);
                    persistence.saveStep(executionId, nodeId, nodeType, input,
                            StepResult.failed("Unknown node type: " + nodeType), 0);
                    continue;
                }

                // ── Execute with per-node timeout ────────────────
                log.debug("Executing node {} (type={})", nodeId, nodeType);
                long nodeStart = System.currentTimeMillis();
                StepResult result = executeWithTimeout(executor, nodeType, nodeData, input);
                long nodeDuration = System.currentTimeMillis() - nodeStart;

                // Save step (single DB write including duration)
                persistence.saveStep(executionId, nodeId, nodeType, input, result, (int) nodeDuration);

                if (result.status() == StepStatus.WAITING) {
                    // The node has nothing to do but wait. Write down where we are, hand the
                    // thread back, and let WorkflowResumeJob pick it up when it is due —
                    // rather than parking a pool thread on a clock.
                    outputs.put(nodeId, result.output());
                    long workedThisSegment = workingMsSoFar + (System.currentTimeMillis() - startTime);
                    persistence.suspendExecution(executionId, result.resumeAt(),
                            snapshot(outputs, skippedNodes, nextNodeAfter(order, nodeId)),
                            workedThisSegment);
                    log.debug("Execution {} suspended at node {} until {}",
                            executionId, nodeId, result.resumeAt());
                    return;
                }

                if (result.status() == StepStatus.FAILED) {
                    log.warn("Node {} failed ({}ms): {}", nodeId, nodeDuration, result.errorMessage());
                    persistence.completeExecution(executionId, ExecutionStatus.FAILED, result.errorMessage(), startTime);
                    return;
                }

                if (result.status() == StepStatus.SKIPPED) {
                    skippedNodes.add(nodeId);
                } else {
                    outputs.put(nodeId, result.output());
                }
            }

            persistence.completeExecution(executionId, ExecutionStatus.COMPLETED, null, startTime);
        } catch (Exception e) {
            log.error("Workflow execution {} failed: {}", executionId, e.getMessage(), e);
            try {
                persistence.completeExecution(executionId, ExecutionStatus.FAILED, e.getMessage(), startTime);
            } catch (Exception pe) {
                // DB unreachable — execution stays RUNNING, recovery job will mark it FAILED after threshold
                log.error("Failed to persist FAILED status for execution {} (recovery job will handle): {}",
                        executionId, pe.getMessage());
            }
        }
    }

    /**
     * The node to continue from, or null when the delay was the last node in the order.
     *
     * <p>Null means the resumed run finds nothing left to do and completes, which is the correct
     * outcome for a workflow that ends on a delay.
     */
    private String nextNodeAfter(List<String> order, String nodeId) {
        int i = order.indexOf(nodeId);
        return (i >= 0 && i + 1 < order.size()) ? order.get(i + 1) : null;
    }

    /**
     * Everything needed to put the execution back together, and no more.
     *
     * <p>Deliberately not the whole engine state: the definition and trigger data are already
     * persisted on the row, and the edge maps are derived from the definition, so re-deriving
     * them on resume is cheaper than storing them and cannot go stale against an edited
     * workflow.
     */
    private JsonNode snapshot(Map<String, JsonNode> outputs, Set<String> skipped, String resumeFrom) {
        ObjectNode state = objectMapper.createObjectNode();
        ObjectNode out = objectMapper.createObjectNode();
        outputs.forEach(out::set);
        state.set("outputs", out);
        ArrayNode skippedArray = objectMapper.createArrayNode();
        skipped.forEach(skippedArray::add);
        state.set("skipped", skippedArray);
        if (resumeFrom != null) {
            state.put("resumeFrom", resumeFrom);
        }
        return state;
    }

    // ── Per-node timeout enforcement ────────────────────────────────────

    /**
     * Execute a node with a per-type timeout.
     * If the node takes too long, returns FAILED with a timeout message.
     */
    private StepResult executeWithTimeout(NodeExecutor executor, String nodeType,
                                           JsonNode nodeData, JsonNode input) {
        long timeoutMs = nodeTimeouts.getOrDefault(nodeType, defaultNodeTimeoutMs);
        // Capture depth from calling thread (workflow-* pool) and propagate
        // to nodeTimeoutExecutor thread — critical for recursion guard in CreateEventNodeExecutor.
        // The tenant crosses the same boundary for the same reason, but that half is the pool's
        // job: nodeTimeoutExecutor is wrapped in TenantPropagatingTaskDecorator.
        int callerDepth = WorkflowTriggerService.getCurrentDepth();
        Future<StepResult> future;
        try {
            future = nodeTimeoutExecutor.submit(() -> {
                WorkflowTriggerService.setCurrentDepth(callerDepth);
                try {
                    return executor.execute(nodeData, input);
                } finally {
                    WorkflowTriggerService.clearCurrentDepth();
                }
            });
        } catch (RejectedExecutionException e) {
            rejectedNodeCounter.increment();
            log.warn("Node execution rejected due to pool saturation (type={}, poolSize={}, queueSize={})",
                    nodeType, nodeTimeoutPool.getCorePoolSize(), nodeTimeoutPool.getQueue().size());
            return StepResult.failed("Node execution rejected: workflow engine at capacity");
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // interrupt the node thread
            return StepResult.failed(String.format("Node timeout: %s exceeded %ds limit",
                    nodeType, timeoutMs / 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return StepResult.failed("Node execution interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return StepResult.failed("Node execution error: " +
                    (cause != null ? cause.getMessage() : e.getMessage()));
        }
    }

    /**
     * Topological sort (Kahn's algorithm) for DAG execution order.
     */
    private List<String> topologicalSort(Set<String> nodeIds, Map<String, List<String>> incomingEdges) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeIds) {
            inDegree.put(id, 0);
        }
        for (Map.Entry<String, List<String>> entry : incomingEdges.entrySet()) {
            if (nodeIds.contains(entry.getKey())) {
                inDegree.put(entry.getKey(), entry.getValue().size());
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);
            result.add(current);

            // Find nodes that depend on current
            for (Map.Entry<String, List<String>> entry : incomingEdges.entrySet()) {
                if (entry.getValue().contains(current) && nodeIds.contains(entry.getKey())) {
                    int newDegree = inDegree.get(entry.getKey()) - 1;
                    inDegree.put(entry.getKey(), newDegree);
                    if (newDegree <= 0 && !visited.contains(entry.getKey())) {
                        queue.add(entry.getKey());
                    }
                }
            }
        }

        // Add any unvisited nodes (isolated) at end
        for (String id : nodeIds) {
            if (!visited.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private record EdgeInfo(String target, String sourceHandle) {}
}
