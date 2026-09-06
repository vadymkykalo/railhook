package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Delay node — asks the engine to continue this execution later.
 *
 * <p>Config: {@code delaySeconds} (int, default 5, max 300). Passes input through unchanged.
 *
 * <p>This used to be a {@code Thread.sleep}. The workflow pool is core-size 4 / max-size 8 for
 * the whole deployment, and a delay may be configured up to 300 seconds, so eight delay nodes —
 * one badly-configured workflow, or eight ordinary ones that happened to overlap — occupied
 * every thread for five minutes and no workflow belonging to any organization ran at all. The
 * threads were not doing work; they were watching a clock, which a database column does for
 * free.
 *
 * <p>So the node computes when it is due and returns; the engine records the execution's
 * position and releases the thread, and {@code WorkflowResumeJob} continues it. The upper bound
 * survives only as a guard against a typo — nothing about a suspended execution costs more when
 * it is longer, so the cap is now the one thing here that could safely be raised.
 */
@Component
@Slf4j
public class DelayNodeExecutor implements NodeExecutor {

    private static final int MAX_DELAY_SECONDS = 300;
    private static final int DEFAULT_DELAY_SECONDS = 5;

    @Override
    public String getType() {
        return "delay";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        int delaySeconds = DEFAULT_DELAY_SECONDS;
        if (nodeConfig.has("delaySeconds")) {
            delaySeconds = nodeConfig.get("delaySeconds").asInt(DEFAULT_DELAY_SECONDS);
        }
        delaySeconds = Math.max(1, Math.min(delaySeconds, MAX_DELAY_SECONDS));

        Instant resumeAt = Instant.now().plusSeconds(delaySeconds);
        log.debug("Delay node: suspending until {} ({}s)", resumeAt, delaySeconds);
        return StepResult.waiting(resumeAt, input);
    }
}
