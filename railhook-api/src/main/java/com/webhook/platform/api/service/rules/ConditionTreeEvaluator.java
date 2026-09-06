package com.webhook.platform.api.service.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.webhook.platform.api.dto.ConditionNode;
import com.webhook.platform.api.dto.ConditionNode.Group;
import com.webhook.platform.api.dto.ConditionNode.Predicate;
import com.webhook.platform.api.dto.ConditionNode.PredicateOperator;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates a {@link ConditionNode} tree against a parsed JSON event payload.
 * <p>
 * Supports nested AND/OR/NOT groups with recursive evaluation.
 * Field values are resolved once and cached per event (memoization).
 * No reflection, no script engines — pure switch-dispatch for maximum throughput.
 */
@Slf4j
public class ConditionTreeEvaluator {

    private static final int MAX_REGEX_LENGTH = 256;
    private static final long REGEX_TIMEOUT_MS = 200;
    private static final int REGEX_CACHE_MAX_SIZE = 256;

    @SuppressWarnings("serial")
    private static final Map<String, Pattern> REGEX_CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
            return size() > REGEX_CACHE_MAX_SIZE;
        }
    };

    private ConditionTreeEvaluator() {
    }

    /**
     * Evaluate a condition tree against event JSON.
     *
     * @param root       condition tree root (may be null = match all)
     * @param eventJson  parsed event JSON
     * @param fieldCache mutable cache for resolved field values (shared per event)
     * @return true if conditions match (or no conditions present)
     */
    public static boolean evaluate(ConditionNode root, JsonNode eventJson, Map<String, JsonNode> fieldCache) {
        if (root == null) {
            return true; // no conditions = always match
        }
        return evaluateNode(root, eventJson, fieldCache);
    }

    private static boolean evaluateNode(ConditionNode node, JsonNode eventJson, Map<String, JsonNode> fieldCache) {
        if (node instanceof Group group) {
            return evaluateGroup(group, eventJson, fieldCache);
        } else if (node instanceof Predicate pred) {
            return evaluatePredicate(pred, eventJson, fieldCache);
        }
        return false;
    }

    // ─── Group evaluation ───────────────────────────────────────────────

    private static boolean evaluateGroup(Group group, JsonNode eventJson, Map<String, JsonNode> fieldCache) {
        if (group.getOp() == null || group.getChildren() == null || group.getChildren().isEmpty()) {
            return true; // empty group = match all
        }

        return switch (group.getOp()) {
            case AND -> {
                for (ConditionNode child : group.getChildren()) {
                    if (!evaluateNode(child, eventJson, fieldCache)) {
                        yield false; // short-circuit
                    }
                }
                yield true;
            }
            case OR -> {
                for (ConditionNode child : group.getChildren()) {
                    if (evaluateNode(child, eventJson, fieldCache)) {
                        yield true; // short-circuit
                    }
                }
                yield false;
            }
            case NOT -> {
                // NOT applies to first child only
                ConditionNode child = group.getChildren().get(0);
                yield !evaluateNode(child, eventJson, fieldCache);
            }
        };
    }

    // ─── Predicate evaluation ───────────────────────────────────────────

    private static boolean evaluatePredicate(Predicate pred, JsonNode eventJson, Map<String, JsonNode> fieldCache) {
        if (pred.getField() == null || pred.getOperator() == null) {
            return false;
        }

        String field = pred.getField();
        PredicateOperator op = pred.getOperator();
        Object value = pred.getValue();
        boolean ci = Boolean.TRUE.equals(pred.getCaseInsensitive());

        // Presence operators — don't need resolved value
        if (op == PredicateOperator.EXISTS) {
            JsonNode node = resolveField(field, eventJson, fieldCache);
            return node != null && !node.isMissingNode();
        }
        if (op == PredicateOperator.NOT_EXISTS) {
            JsonNode node = resolveField(field, eventJson, fieldCache);
            return node == null || node.isMissingNode();
        }
        if (op == PredicateOperator.IS_NULL) {
            JsonNode node = resolveField(field, eventJson, fieldCache);
            return node == null || node.isMissingNode() || node.isNull();
        }
        if (op == PredicateOperator.NOT_NULL) {
            JsonNode node = resolveField(field, eventJson, fieldCache);
            return node != null && !node.isMissingNode() && !node.isNull();
        }

        // Resolve field value
        JsonNode fieldNode = resolveField(field, eventJson, fieldCache);
        if (fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull()) {
            return false; // missing field → comparison fails
        }

        return switch (op) {
            case EQ -> compareEquals(fieldNode, value, ci);
            case NEQ -> !compareEquals(fieldNode, value, ci);
            case GT -> { int c = compareNumeric(fieldNode, value); yield c != COMPARE_ERROR && c > 0; }
            case GTE -> { int c = compareNumeric(fieldNode, value); yield c != COMPARE_ERROR && c >= 0; }
            case LT -> { int c = compareNumeric(fieldNode, value); yield c != COMPARE_ERROR && c < 0; }
            case LTE -> { int c = compareNumeric(fieldNode, value); yield c != COMPARE_ERROR && c <= 0; }
            case BETWEEN -> evaluateBetween(fieldNode, value);
            case CONTAINS -> textOp(fieldNode, value, ci, String::contains);
            case NOT_CONTAINS -> textOp(fieldNode, value, ci, (a, b) -> !a.contains(b));
            case STARTS_WITH -> textOp(fieldNode, value, ci, String::startsWith);
            case ENDS_WITH -> textOp(fieldNode, value, ci, String::endsWith);
            case IN -> evaluateIn(fieldNode, value, ci);
            case NOT_IN -> !evaluateIn(fieldNode, value, ci);
            case REGEX -> evaluateRegex(fieldNode, value);
            default -> false;
        };
    }

    // ─── Field resolution with memoization ──────────────────────────────

    static JsonNode resolveField(String path, JsonNode root, Map<String, JsonNode> cache) {
        if (path == null || root == null) return null;

        // Strip leading "$." or "payload." prefix
        String cleanPath = path;
        if (cleanPath.startsWith("$.")) cleanPath = cleanPath.substring(2);

        return cache.computeIfAbsent(cleanPath, p -> traversePath(p, root));
    }

    private static JsonNode traversePath(String path, JsonNode node) {
        String[] segments = path.split("\\.");
        JsonNode current = node;
        for (String segment : segments) {
            if (current == null || current.isMissingNode() || current.isNull()) return null;

            // Handle array index: items[0]
            int bracketIdx = segment.indexOf('[');
            if (bracketIdx >= 0 && segment.endsWith("]")) {
                String fieldName = segment.substring(0, bracketIdx);
                String indexStr = segment.substring(bracketIdx + 1, segment.length() - 1);
                current = current.path(fieldName);
                if (current.isArray()) {
                    try {
                        current = current.get(Integer.parseInt(indexStr));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            } else {
                current = current.path(segment);
            }
        }
        return current;
    }

    // ─── Comparison helpers ─────────────────────────────────────────────

    private static boolean compareEquals(JsonNode fieldNode, Object value, boolean ci) {
        if (value == null) return fieldNode.isNull();
        String fieldText = asText(fieldNode);
        String valueText = String.valueOf(value);

        // Numeric comparison
        if (fieldNode.isNumber() && isNumeric(valueText)) {
            return new BigDecimal(fieldText).compareTo(new BigDecimal(valueText)) == 0;
        }
        // Boolean comparison
        if (fieldNode.isBoolean()) {
            return fieldText.equals(valueText);
        }
        return ci ? fieldText.equalsIgnoreCase(valueText) : fieldText.equals(valueText);
    }

    private static final int COMPARE_ERROR = Integer.MIN_VALUE;

    private static int compareNumeric(JsonNode fieldNode, Object value) {
        try {
            BigDecimal fieldNum = new BigDecimal(asText(fieldNode));
            BigDecimal valueNum = new BigDecimal(String.valueOf(value));
            return fieldNum.compareTo(valueNum);
        } catch (NumberFormatException e) {
            return COMPARE_ERROR;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean evaluateBetween(JsonNode fieldNode, Object value) {
        if (!(value instanceof List<?> list) || list.size() != 2) return false;
        try {
            BigDecimal fieldNum = new BigDecimal(asText(fieldNode));
            BigDecimal low = new BigDecimal(String.valueOf(list.get(0)));
            BigDecimal high = new BigDecimal(String.valueOf(list.get(1)));
            return fieldNum.compareTo(low) >= 0 && fieldNum.compareTo(high) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface TextMatcher {
        boolean test(String fieldText, String valueText);
    }

    private static boolean textOp(JsonNode fieldNode, Object value, boolean ci, TextMatcher matcher) {
        String fieldText = asText(fieldNode);
        String valueText = String.valueOf(value);
        if (ci) {
            fieldText = fieldText.toLowerCase();
            valueText = valueText.toLowerCase();
        }
        return matcher.test(fieldText, valueText);
    }

    @SuppressWarnings("unchecked")
    private static boolean evaluateIn(JsonNode fieldNode, Object value, boolean ci) {
        String fieldText = asText(fieldNode);
        if (ci) fieldText = fieldText.toLowerCase();
        if (value instanceof List<?> list) {
            String ft = fieldText;
            return list.stream().anyMatch(v -> {
                String vs = String.valueOf(v);
                return ci ? ft.equals(vs.toLowerCase()) : ft.equals(vs);
            });
        }
        String vs = String.valueOf(value);
        return ci ? fieldText.equalsIgnoreCase(vs) : fieldText.equals(vs);
    }

    private static boolean evaluateRegex(JsonNode fieldNode, Object value) {
        if (value == null) return false;
        String patternStr = String.valueOf(value);
        if (patternStr.length() > MAX_REGEX_LENGTH) {
            log.warn("Regex pattern too long ({} > {}), rejecting", patternStr.length(), MAX_REGEX_LENGTH);
            return false;
        }
        try {
            Pattern pattern;
            synchronized (REGEX_CACHE) {
                pattern = REGEX_CACHE.computeIfAbsent(patternStr, Pattern::compile);
            }
            CharSequence input = new InterruptibleCharSequence(asText(fieldNode), REGEX_TIMEOUT_MS);
            return pattern.matcher(input).find();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex pattern: {}", patternStr);
            return false;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException || e.getMessage() != null && e.getMessage().contains("timed out")) {
                log.warn("Regex evaluation timed out after {}ms for pattern: {}", REGEX_TIMEOUT_MS, patternStr);
                return false;
            }
            throw e;
        }
    }

    /**
     * CharSequence wrapper that aborts regex matching after a deadline.
     * Each charAt() call checks elapsed time — if exceeded, throws to break
     * catastrophic backtracking (ReDoS protection).
     */
    private static final class InterruptibleCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadlineNanos;

        InterruptibleCharSequence(CharSequence inner, long timeoutMs) {
            this.inner = inner;
            this.deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RuntimeException("Regex evaluation timed out");
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(inner.subSequence(start, end),
                    Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000));
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }

    private static String asText(JsonNode node) {
        return node.isTextual() ? node.textValue() : node.toString();
    }

    private static boolean isNumeric(String s) {
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Create a fresh field cache for a single event evaluation.
     * Share this across all rules evaluated for the same event.
     */
    public static Map<String, JsonNode> newFieldCache() {
        return new HashMap<>(16);
    }
}
