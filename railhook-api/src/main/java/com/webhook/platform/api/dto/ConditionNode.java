package com.webhook.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <pre>{@code
 * {
 *   "type": "group",
 *   "op": "AND",
 *   "children": [
 *     { "type": "predicate", "field": "payload.data.amount", "operator": "GTE", "value": 1000, "valueType": "NUMBER" },
 *     { "type": "predicate", "field": "event.type", "operator": "EQ", "value": "order.completed", "valueType": "STRING" }
 *   ]
 * }
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConditionNode.Group.class, name = "group"),
        @JsonSubTypes.Type(value = ConditionNode.Predicate.class, name = "predicate")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract sealed class ConditionNode permits ConditionNode.Group, ConditionNode.Predicate {

    /** NOT must have exactly one child. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Group extends ConditionNode {
        private GroupOperator op;
        private List<ConditionNode> children;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Predicate extends ConditionNode {
        private String field;
        private PredicateOperator operator;
        private Object value;
        private ValueType valueType;
        private Boolean caseInsensitive;
    }

    public enum GroupOperator {
        AND, OR, NOT
    }

    public enum PredicateOperator {
        EQ, NEQ, CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, IN, NOT_IN, REGEX,
        GT, GTE, LT, LTE, BETWEEN,
        EXISTS, NOT_EXISTS, IS_NULL, NOT_NULL
    }

    public enum ValueType {
        STRING, NUMBER, BOOLEAN, ARRAY_STRING, ARRAY_NUMBER, DATE_TIME
    }
}
