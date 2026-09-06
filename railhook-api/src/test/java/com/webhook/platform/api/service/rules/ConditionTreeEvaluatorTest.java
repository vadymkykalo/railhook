package com.webhook.platform.api.service.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.ConditionNode;
import com.webhook.platform.api.dto.ConditionNode.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionTreeEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, JsonNode> fieldCache;

    @BeforeEach
    void setUp() {
        fieldCache = ConditionTreeEvaluator.newFieldCache();
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    // ─── Null / empty ───────────────────────────────────────────────────

    @Test
    void nullConditions_matchAll() throws Exception {
        assertThat(ConditionTreeEvaluator.evaluate(null, json("{}"), fieldCache)).isTrue();
    }

    @Test
    void emptyGroup_matchAll() throws Exception {
        Group group = Group.builder().op(GroupOperator.AND).children(List.of()).build();
        assertThat(ConditionTreeEvaluator.evaluate(group, json("{}"), fieldCache)).isTrue();
    }

    // ─── Simple predicates ──────────────────────────────────────────────

    @Nested
    class PredicateTests {

        @Test
        void eq_string() throws Exception {
            Predicate p = Predicate.builder()
                    .field("type").operator(PredicateOperator.EQ).value("order.created").valueType(ValueType.STRING)
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"type\":\"order.created\"}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"type\":\"other\"}"), fieldCache)).isFalse();
        }

        @Test
        void eq_number() throws Exception {
            Predicate p = Predicate.builder()
                    .field("data.amount").operator(PredicateOperator.EQ).value(100).valueType(ValueType.NUMBER)
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"data\":{\"amount\":100}}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"data\":{\"amount\":200}}"), fieldCache)).isFalse();
        }

        @Test
        void neq() throws Exception {
            Predicate p = Predicate.builder()
                    .field("status").operator(PredicateOperator.NEQ).value("cancelled").valueType(ValueType.STRING)
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"status\":\"active\"}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"status\":\"cancelled\"}"), fieldCache)).isFalse();
        }

        @Test
        void gt_gte_lt_lte() throws Exception {
            JsonNode event = json("{\"amount\":50}");

            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.GT, 40), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.GT, 50), event, fieldCache)).isFalse();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.GTE, 50), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.LT, 60), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.LTE, 50), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.LTE, 49), event, fieldCache)).isFalse();
        }

        @Test
        void between() throws Exception {
            Predicate p = Predicate.builder()
                    .field("amount").operator(PredicateOperator.BETWEEN).value(List.of(10, 100)).valueType(ValueType.NUMBER)
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"amount\":50}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"amount\":10}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"amount\":100}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"amount\":101}"), fieldCache)).isFalse();
        }

        @Test
        void contains_startsWith_endsWith() throws Exception {
            JsonNode event = json("{\"email\":\"user@example.com\"}");

            assertThat(ConditionTreeEvaluator.evaluate(
                    pred("email", PredicateOperator.CONTAINS, "example"), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(
                    pred("email", PredicateOperator.STARTS_WITH, "user@"), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(
                    pred("email", PredicateOperator.ENDS_WITH, ".com"), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(
                    pred("email", PredicateOperator.NOT_CONTAINS, "foo"), event, fieldCache)).isTrue();
        }

        @Test
        void in_notIn() throws Exception {
            Predicate inPred = Predicate.builder()
                    .field("currency").operator(PredicateOperator.IN).value(List.of("USD", "EUR")).valueType(ValueType.ARRAY_STRING)
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(inPred, json("{\"currency\":\"USD\"}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(inPred, json("{\"currency\":\"GBP\"}"), fieldCache)).isFalse();

            fieldCache.clear();
            Predicate notInPred = Predicate.builder()
                    .field("currency").operator(PredicateOperator.NOT_IN).value(List.of("USD", "EUR")).valueType(ValueType.ARRAY_STRING)
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(notInPred, json("{\"currency\":\"GBP\"}"), fieldCache)).isTrue();
        }

        @Test
        void regex() throws Exception {
            Predicate p = Predicate.builder()
                    .field("type").operator(PredicateOperator.REGEX).value("^order\\..*").valueType(ValueType.STRING)
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"type\":\"order.created\"}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"type\":\"payment.done\"}"), fieldCache)).isFalse();
        }

        @Test
        void exists_notExists() throws Exception {
            Predicate exists = Predicate.builder()
                    .field("data.email").operator(PredicateOperator.EXISTS).build();
            Predicate notExists = Predicate.builder()
                    .field("data.email").operator(PredicateOperator.NOT_EXISTS).build();

            assertThat(ConditionTreeEvaluator.evaluate(exists, json("{\"data\":{\"email\":\"a@b.com\"}}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(exists, json("{\"data\":{}}"), fieldCache)).isFalse();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(notExists, json("{\"data\":{}}"), fieldCache)).isTrue();
        }

        @Test
        void isNull_notNull() throws Exception {
            Predicate isNull = Predicate.builder()
                    .field("data.ref").operator(PredicateOperator.IS_NULL).build();
            Predicate notNull = Predicate.builder()
                    .field("data.ref").operator(PredicateOperator.NOT_NULL).build();

            assertThat(ConditionTreeEvaluator.evaluate(isNull, json("{\"data\":{\"ref\":null}}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(isNull, json("{\"data\":{}}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(notNull, json("{\"data\":{\"ref\":\"abc\"}}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(notNull, json("{\"data\":{\"ref\":null}}"), fieldCache)).isFalse();
        }

        @Test
        void caseInsensitive() throws Exception {
            Predicate p = Predicate.builder()
                    .field("status").operator(PredicateOperator.EQ).value("active").valueType(ValueType.STRING)
                    .caseInsensitive(true)
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"status\":\"ACTIVE\"}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"status\":\"Active\"}"), fieldCache)).isTrue();
        }

        @Test
        void missingField_returnsFalse() throws Exception {
            Predicate p = pred("nonexistent", PredicateOperator.EQ, "something");
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"other\":1}"), fieldCache)).isFalse();
        }

        @Test
        void arrayIndex_field() throws Exception {
            Predicate p = pred("items[0].name", PredicateOperator.EQ, "widget");
            assertThat(ConditionTreeEvaluator.evaluate(p,
                    json("{\"items\":[{\"name\":\"widget\"},{\"name\":\"gadget\"}]}"), fieldCache)).isTrue();
        }
    }

    // ─── Groups ─────────────────────────────────────────────────────────

    @Nested
    class GroupTests {

        @Test
        void and_allTrue() throws Exception {
            Group group = Group.builder()
                    .op(GroupOperator.AND)
                    .children(List.of(
                            pred("type", PredicateOperator.EQ, "order.created"),
                            pred("data.amount", PredicateOperator.GT, 100)
                    ))
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(group,
                    json("{\"type\":\"order.created\",\"data\":{\"amount\":200}}"), fieldCache)).isTrue();
        }

        @Test
        void and_oneFalse() throws Exception {
            Group group = Group.builder()
                    .op(GroupOperator.AND)
                    .children(List.of(
                            pred("type", PredicateOperator.EQ, "order.created"),
                            pred("data.amount", PredicateOperator.GT, 300)
                    ))
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(group,
                    json("{\"type\":\"order.created\",\"data\":{\"amount\":200}}"), fieldCache)).isFalse();
        }

        @Test
        void or_anyTrue() throws Exception {
            Group group = Group.builder()
                    .op(GroupOperator.OR)
                    .children(List.of(
                            pred("type", PredicateOperator.EQ, "refund"),
                            pred("type", PredicateOperator.EQ, "order.created")
                    ))
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(group,
                    json("{\"type\":\"order.created\"}"), fieldCache)).isTrue();
        }

        @Test
        void or_nonTrue() throws Exception {
            Group group = Group.builder()
                    .op(GroupOperator.OR)
                    .children(List.of(
                            pred("type", PredicateOperator.EQ, "refund"),
                            pred("type", PredicateOperator.EQ, "cancel")
                    ))
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(group,
                    json("{\"type\":\"order.created\"}"), fieldCache)).isFalse();
        }

        @Test
        void not_negation() throws Exception {
            Group group = Group.builder()
                    .op(GroupOperator.NOT)
                    .children(List.of(
                            pred("region", PredicateOperator.EQ, "EU")
                    ))
                    .build();
            assertThat(ConditionTreeEvaluator.evaluate(group,
                    json("{\"region\":\"US\"}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(group,
                    json("{\"region\":\"EU\"}"), fieldCache)).isFalse();
        }
    }

    // ─── Nested groups: (A AND B) OR C ──────────────────────────────────

    @Nested
    class NestedGroupTests {

        @Test
        void nestedAndInsideOr() throws Exception {
            // (type=order.created AND amount>500) OR type=refund
            Group tree = Group.builder()
                    .op(GroupOperator.OR)
                    .children(List.of(
                            Group.builder()
                                    .op(GroupOperator.AND)
                                    .children(List.of(
                                            pred("type", PredicateOperator.EQ, "order.created"),
                                            pred("total", PredicateOperator.GT, 500)
                                    ))
                                    .build(),
                            pred("type", PredicateOperator.EQ, "refund")
                    ))
                    .build();

            // case 1: order.created AND total>500 → match via first branch
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"order.created\",\"total\":1000}"), fieldCache)).isTrue();

            // case 2: refund → match via second branch
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"refund\",\"total\":10}"), fieldCache)).isTrue();

            // case 3: order.created but total=200 → no match (neither branch)
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"order.created\",\"total\":200}"), fieldCache)).isFalse();

            // case 4: payment.done → no match
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"payment.done\",\"total\":1000}"), fieldCache)).isFalse();
        }

        @Test
        void notInsideAnd() throws Exception {
            // type=order.* AND NOT(region=EU)
            Group tree = Group.builder()
                    .op(GroupOperator.AND)
                    .children(List.of(
                            pred("type", PredicateOperator.STARTS_WITH, "order."),
                            Group.builder()
                                    .op(GroupOperator.NOT)
                                    .children(List.of(pred("region", PredicateOperator.EQ, "EU")))
                                    .build()
                    ))
                    .build();

            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"order.created\",\"region\":\"US\"}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"order.created\",\"region\":\"EU\"}"), fieldCache)).isFalse();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"payment.done\",\"region\":\"US\"}"), fieldCache)).isFalse();
        }

        @Test
        void deeplyNested3Levels() throws Exception {
            // OR( AND(A, B), AND(C, NOT(D)) )
            Group tree = Group.builder()
                    .op(GroupOperator.OR)
                    .children(List.of(
                            Group.builder()
                                    .op(GroupOperator.AND)
                                    .children(List.of(
                                            pred("type", PredicateOperator.EQ, "payment"),
                                            pred("amount", PredicateOperator.GTE, 1000)
                                    ))
                                    .build(),
                            Group.builder()
                                    .op(GroupOperator.AND)
                                    .children(List.of(
                                            pred("type", PredicateOperator.EQ, "refund"),
                                            Group.builder()
                                                    .op(GroupOperator.NOT)
                                                    .children(List.of(pred("reason", PredicateOperator.EQ, "duplicate")))
                                                    .build()
                                    ))
                                    .build()
                    ))
                    .build();

            // payment >= 1000 → match first branch
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"payment\",\"amount\":1500,\"reason\":\"whatever\"}"), fieldCache)).isTrue();

            // refund + reason != duplicate → match second branch
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"refund\",\"amount\":10,\"reason\":\"customer_request\"}"), fieldCache)).isTrue();

            // refund + reason = duplicate → NOT blocks it
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"refund\",\"amount\":10,\"reason\":\"duplicate\"}"), fieldCache)).isFalse();

            // payment < 1000 → no match
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"payment\",\"amount\":500,\"reason\":\"whatever\"}"), fieldCache)).isFalse();
        }
    }

    // ─── JSON serialization round-trip ──────────────────────────────────

    @Test
    void jsonRoundTrip() throws Exception {
        String treeJson = """
                {
                  "type": "group",
                  "op": "AND",
                  "children": [
                    {
                      "type": "predicate",
                      "field": "event.type",
                      "operator": "EQ",
                      "value": "payment_succeeded",
                      "valueType": "STRING"
                    },
                    {
                      "type": "predicate",
                      "field": "payload.data.amount",
                      "operator": "GT",
                      "value": 1000,
                      "valueType": "NUMBER"
                    }
                  ]
                }
                """;

        ConditionNode parsed = mapper.readValue(treeJson, ConditionNode.class);
        assertThat(parsed).isInstanceOf(Group.class);

        Group group = (Group) parsed;
        assertThat(group.getOp()).isEqualTo(GroupOperator.AND);
        assertThat(group.getChildren()).hasSize(2);
        assertThat(group.getChildren().get(0)).isInstanceOf(Predicate.class);
        assertThat(group.getChildren().get(1)).isInstanceOf(Predicate.class);

        // Serialize back and verify
        String serialized = mapper.writeValueAsString(parsed);
        ConditionNode reparsed = mapper.readValue(serialized, ConditionNode.class);
        assertThat(reparsed).isInstanceOf(Group.class);

        Group regroup = (Group) reparsed;
        assertThat(regroup.getOp()).isEqualTo(GroupOperator.AND);
        assertThat(regroup.getChildren()).hasSize(2);
    }

    @Test
    void jsonRoundTrip_nestedNotGroup() throws Exception {
        String treeJson = """
                {
                  "type": "group",
                  "op": "OR",
                  "children": [
                    {
                      "type": "group",
                      "op": "NOT",
                      "children": [
                        { "type": "predicate", "field": "region", "operator": "EQ", "value": "EU", "valueType": "STRING" }
                      ]
                    },
                    { "type": "predicate", "field": "vip", "operator": "EQ", "value": true, "valueType": "BOOLEAN" }
                  ]
                }
                """;

        ConditionNode parsed = mapper.readValue(treeJson, ConditionNode.class);
        assertThat(parsed).isInstanceOf(Group.class);

        Group root = (Group) parsed;
        assertThat(root.getOp()).isEqualTo(GroupOperator.OR);
        assertThat(root.getChildren()).hasSize(2);
        assertThat(root.getChildren().get(0)).isInstanceOf(Group.class);
        assertThat(((Group) root.getChildren().get(0)).getOp()).isEqualTo(GroupOperator.NOT);
    }

    // ─── Regression: ReDoS & compareNumeric fixes ───────────────────────

    @Nested
    class SecurityRegressionTests {

        @Test
        void regex_tooLong_rejected() throws Exception {
            String longPattern = "a".repeat(300);
            Predicate p = Predicate.builder()
                    .field("name").operator(PredicateOperator.REGEX).value(longPattern).build();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"name\":\"aaa\"}"), fieldCache)).isFalse();
        }

        @Test
        void regex_catastrophicBacktracking_timesOut() throws Exception {
            // Classic ReDoS pattern: (a+)+$ against "aaaaaaaaaaaaaaaaaa!"
            Predicate p = Predicate.builder()
                    .field("val").operator(PredicateOperator.REGEX).value("(a+)+$").build();
            long start = System.currentTimeMillis();
            boolean result = ConditionTreeEvaluator.evaluate(p,
                    json("{\"val\":\"" + "a".repeat(30) + "!\"}"), fieldCache);
            long elapsed = System.currentTimeMillis() - start;
            assertThat(result).isFalse();
            // Must complete within 1 second (timeout is 200ms + overhead)
            assertThat(elapsed).isLessThan(1000);
        }

        @Test
        void compareNumeric_nonNumericField_gteReturnsFalse() throws Exception {
            // Before fix: "hello" GTE 42 returned true (NFE → 0, 0 >= 0 → true)
            Predicate p = pred("name", PredicateOperator.GTE, 42);
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"name\":\"hello\"}"), fieldCache)).isFalse();
        }

        @Test
        void compareNumeric_nonNumericField_lteReturnsFalse() throws Exception {
            Predicate p = pred("name", PredicateOperator.LTE, 42);
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"name\":\"hello\"}"), fieldCache)).isFalse();
        }

        @Test
        void compareNumeric_nonNumericField_gtReturnsFalse() throws Exception {
            Predicate p = pred("name", PredicateOperator.GT, 42);
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"name\":\"hello\"}"), fieldCache)).isFalse();
        }

        @Test
        void compareNumeric_nonNumericField_ltReturnsFalse() throws Exception {
            Predicate p = pred("name", PredicateOperator.LT, 42);
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"name\":\"hello\"}"), fieldCache)).isFalse();
        }

        @Test
        void compareNumeric_validNumeric_stillWorks() throws Exception {
            JsonNode event = json("{\"amount\":50}");
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.GTE, 50), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.LTE, 50), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.GT, 49), event, fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(pred("amount", PredicateOperator.LT, 51), event, fieldCache)).isTrue();
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static Predicate pred(String field, PredicateOperator op, Object value) {
        return Predicate.builder().field(field).operator(op).value(value).build();
    }
}
