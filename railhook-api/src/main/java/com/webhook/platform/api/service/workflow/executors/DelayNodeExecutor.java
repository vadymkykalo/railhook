package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Returns a due time instead of sleeping, which let eight delay nodes hold the shared pool. */
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
