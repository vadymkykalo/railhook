package com.webhook.platform.api.service.rules;

import com.webhook.platform.api.domain.entity.RuleAction.ActionType;
import com.webhook.platform.api.dto.ConditionNode;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * In-memory compiled representation of a Rule for fast evaluation.
 * Created once on cache load, reused for every event.
 */
@Getter
@Builder
public class CompiledRule {

    private final UUID ruleId;
    private final UUID projectId;
    private final String name;
    private final int priority;

    /** NULL = catch-all (matches every event type) */
    private final String eventTypePattern;

    /** Pre-parsed condition tree (avoids JSON parsing per event). NULL = match all. */
    private final ConditionNode conditionTree;

    /** Pre-resolved actions */
    private final List<CompiledAction> actions;

    @Getter
    @Builder
    public static class CompiledAction {
        private final UUID actionId;
        private final ActionType type;
        private final UUID endpointId;
        private final UUID transformationId;
        private final String configJson;
        private final int sortOrder;
    }
}
