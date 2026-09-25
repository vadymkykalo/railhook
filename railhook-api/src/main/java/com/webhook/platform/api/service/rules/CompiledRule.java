package com.webhook.platform.api.service.rules;

import com.webhook.platform.api.domain.entity.RuleAction.ActionType;
import com.webhook.platform.api.dto.ConditionNode;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class CompiledRule {

    private final UUID ruleId;
    private final UUID projectId;
    private final String name;
    private final int priority;

    /** Null matches every event type. */
    private final String eventTypePattern;

    /** Parsed once so events do not pay for JSON parsing. Null matches all. */
    private final ConditionNode conditionTree;

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
