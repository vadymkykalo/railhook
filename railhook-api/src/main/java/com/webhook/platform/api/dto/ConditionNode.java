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
 * Polymorphic condition tree node.
 * <p>
 * Two types:
 * <ul>
 *   <li><b>group</b> — logical operator (AND / OR / NOT) with children nodes</li>
 *   <li><b>predicate</b> — atomic field comparison (field + operator + value)</li>
 * </ul>
 * <p>
 * Example JSON:
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

    /**
     * Logical group node: AND / OR / NOT.
     * NOT must have exactly 1 child.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Group extends ConditionNode {
        private GroupOperator op;
        private List<ConditionNode> children;
    }

    /**
     * Atomic predicate: field + operator + value.
     */
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

        /** Case-insensitive string comparison (default: false) */
        private Boolean caseInsensitive;
    }

    // ─── Enums ──────────────────────────────────────────────────────────

    public enum GroupOperator {
        AND, OR, NOT
    }

    public enum PredicateOperator {
        // String
        EQ, NEQ, CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, IN, NOT_IN, REGEX,
        // Numeric / comparable
        GT, GTE, LT, LTE, BETWEEN,
        // Presence
        EXISTS, NOT_EXISTS, IS_NULL, NOT_NULL
    }

    public enum ValueType {
        STRING, NUMBER, BOOLEAN, ARRAY_STRING, ARRAY_NUMBER, DATE_TIME
    }
}
