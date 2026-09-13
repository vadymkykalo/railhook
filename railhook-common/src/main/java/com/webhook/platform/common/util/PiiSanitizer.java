package com.webhook.platform.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII sanitizer for webhook payloads.
 * Masks personally identifiable information based on configurable rules.
 * <p>
 * Supports built-in patterns (email, phone, card numbers — flat or inside a card object)
 * and custom JSON paths.
 * Three masking styles: FULL (replace entirely), PARTIAL (show prefix/suffix), HASH (SHA-256 prefix).
 */
public final class PiiSanitizer {

    private PiiSanitizer() {
    }

    public enum MaskStyle {
        FULL,
        PARTIAL,
        HASH
    }

    public static final String BUILTIN_EMAIL = "email";
    public static final String BUILTIN_PHONE = "phone";
    public static final String BUILTIN_CARD = "card";

    /*
     * A key and the string it holds, each read in one pass; whether the pair is PII is decided in
     * code. Asking a regex for "a key containing the keyword" or "a value containing '@'" puts a
     * repetition on both sides of the thing looked for, which a backtracking engine can split
     * many ways over one hostile string however the repetitions are bounded (CodeQL
     * java/polynomial-redos). Nothing here is ambiguous: a key and a value each end at the first
     * quote, so every quote in the payload is a start that stops at the next one.
     */
    private static final Pattern STRING_MEMBER = Pattern.compile("\"([^\"]{1,200})\"\\s*:\\s*\"([^\"]*)\"");

    private enum FlatPii {
        EMAIL(BUILTIN_EMAIL, "mail") {
            @Override
            boolean holds(String value) {
                int at = value.indexOf('@', 1);
                return at > 0 && at < value.length() - 1;
            }
        },
        PHONE(BUILTIN_PHONE, "phone", "mobile", "cell", "tel", "fax") {
            @Override
            boolean holds(String value) {
                int start = value.startsWith("+") ? 1 : 0;
                int length = value.length() - start;
                if (length < 7 || length > 20) {
                    return false;
                }
                for (int i = start; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (!isDigit(c) && !isSpace(c) && c != '-' && c != '(' && c != ')' && c != '.') {
                        return false;
                    }
                }
                return true;
            }
        },
        CARD(BUILTIN_CARD, "card", "pan", "credit", "debit", "account") {
            @Override
            boolean holds(String value) {
                int length = value.length();
                if (length < 13 || length > 20 || !isDigit(value.charAt(0)) || !isDigit(value.charAt(length - 1))) {
                    return false;
                }
                for (int i = 1; i < length - 1; i++) {
                    char c = value.charAt(i);
                    if (!isDigit(c) && !isSpace(c) && c != '-') {
                        return false;
                    }
                }
                return true;
            }
        };

        final String patternName;
        private final String[] keywords;

        FlatPii(String patternName, String... keywords) {
            this.patternName = patternName;
            this.keywords = keywords;
        }

        abstract boolean holds(String value);

        boolean matches(String key, String value) {
            String lower = key.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    return holds(value);
                }
            }
            return false;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isSpace(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
        }
    }

    private static final String[] CARD_OBJECT_MEMBERS = {"number", "pan", "num", "value"};
    private static final String[] CARD_OBJECT_KEYWORDS = {"card", "credit", "debit"};

    /**
     * The same PAN, one level down.
     *
     * <p>{@link FlatPii#CARD} asks the key holding the digits to be card-ish,
     * which is true of {@code "cardNumber"} and {@code "pan"} but not of the
     * shape every payment provider actually sends:
     * {@code {"card": {"number": "4242…"}}}. There the key on the digits is the
     * entirely innocent {@code "number"}, and the only thing saying "card" is
     * the object around it — so a rule an operator had enabled, and which the
     * UI showed as enabled, forwarded the PAN in the clear.
     *
     * <p>The context comes from the object around the member rather than a wider key list
     * because {@code "number"} on its own is not evidence of anything: an order
     * number of the same length must survive untouched, and it does — nothing
     * matches unless a card-ish key opens the object it sits in. It is read backwards from the
     * member, at most a few hundred characters. It used to be a regex lookbehind, which Java
     * evaluates at every position of the payload, so a long one stalled the sanitizer.
     */
    private static boolean isCardObjectMember(String json, int memberStart, String key, String value) {
        if (!equalsAnyIgnoreCase(key, CARD_OBJECT_MEMBERS) || !FlatPii.CARD.holds(value)) {
            return false;
        }
        // "card"\s{0,4}:\s{0,4}{ then up to 300 characters without a brace, then the member.
        int i = memberStart - 1;
        int limit = Math.max(-1, memberStart - 301);
        while (i > limit && json.charAt(i) != '{' && json.charAt(i) != '}') {
            i--;
        }
        if (i <= limit || json.charAt(i) != '{') {
            return false;
        }
        i = skipSpacesBackwards(json, i - 1);
        if (i < 0 || json.charAt(i) != ':') {
            return false;
        }
        i = skipSpacesBackwards(json, i - 1);
        if (i < 0 || json.charAt(i) != '"') {
            return false;
        }
        int keyEnd = i;
        int keyStart = json.lastIndexOf('"', keyEnd - 1);
        if (keyStart < 0) {
            return false;
        }
        String parentKey = json.substring(keyStart + 1, keyEnd).toLowerCase(Locale.ROOT);
        for (String keyword : CARD_OBJECT_KEYWORDS) {
            for (int at = parentKey.indexOf(keyword); at >= 0; at = parentKey.indexOf(keyword, at + 1)) {
                if (at <= 40 && parentKey.length() - at - keyword.length() <= 40) {
                    return true;
                }
            }
        }
        return false;
    }

    /** At most four whitespace characters, as the context always allowed. */
    private static int skipSpacesBackwards(String json, int i) {
        for (int skipped = 0; skipped < 4 && i >= 0 && Character.isWhitespace(json.charAt(i)) && json.charAt(i) <= ' '; skipped++) {
            i--;
        }
        return i;
    }

    private static boolean equalsAnyIgnoreCase(String key, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes a JSON payload string by applying the given rules.
     *
     * @param json  raw JSON payload
     * @param rules list of masking rules to apply
     * @return sanitized JSON with PII masked
     */
    public static String sanitize(String json, List<Rule> rules) {
        if (json == null || json.isBlank() || rules == null || rules.isEmpty()) {
            return json;
        }

        String result = json;
        for (Rule rule : rules) {
            if (!rule.enabled) {
                continue;
            }
            result = applyRule(result, rule);
        }
        return result;
    }

    /**
     * Detects PII patterns in a JSON payload and returns a list of findings.
     * Useful for preview / audit without masking.
     */
    public static List<PiiMatch> detect(String json) {
        List<PiiMatch> matches = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return matches;
        }

        detectFlat(json, FlatPii.EMAIL, matches);
        detectFlat(json, FlatPii.PHONE, matches);
        detectFlat(json, FlatPii.CARD, matches);
        Matcher m = STRING_MEMBER.matcher(json);
        while (m.find()) {
            if (isCardObjectMember(json, m.start(), m.group(1), m.group(2))) {
                matches.add(new PiiMatch(BUILTIN_CARD, m.group(1), m.group(2)));
            }
        }
        return matches;
    }

    private static void detectFlat(String json, FlatPii kind, List<PiiMatch> matches) {
        Matcher m = STRING_MEMBER.matcher(json);
        while (m.find()) {
            if (kind.matches(m.group(1), m.group(2))) {
                matches.add(new PiiMatch(kind.patternName, m.group(1), m.group(2)));
            }
        }
    }

    private static String applyRule(String json, Rule rule) {
        switch (rule.patternName) {
            case BUILTIN_EMAIL:
                return maskFlat(json, FlatPii.EMAIL, rule.maskStyle);
            case BUILTIN_PHONE:
                return maskFlat(json, FlatPii.PHONE, rule.maskStyle);
            case BUILTIN_CARD:
                // Both shapes, because a payload routinely carries only one of them.
                return maskCardObjects(maskFlat(json, FlatPii.CARD, rule.maskStyle), rule.maskStyle);
            default:
                if (rule.jsonPath != null && !rule.jsonPath.isBlank()) {
                    return applyJsonPathRule(json, rule.jsonPath, rule.maskStyle);
                }
                return json;
        }
    }

    private static String maskFlat(String json, FlatPii kind, MaskStyle style) {
        Matcher m = STRING_MEMBER.matcher(json);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            if (kind.matches(key, value)) {
                m.appendReplacement(sb, Matcher.quoteReplacement("\"" + key + "\": \"" + maskValue(value, style) + "\""));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskCardObjects(String json, MaskStyle style) {
        Matcher m = STRING_MEMBER.matcher(json);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            if (isCardObjectMember(json, m.start(), key, value)) {
                m.appendReplacement(sb, Matcher.quoteReplacement("\"" + key + "\": \"" + maskValue(value, style) + "\""));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Applies masking to values at a simple JSON path like "$.user.ssn" or "$.data.*.secret".
     * Supports basic dot-notation and single wildcard (*) for array/object traversal.
     * This is a lightweight regex-based approach, not a full JSONPath implementation.
     */
    private static String applyJsonPathRule(String json, String jsonPath, MaskStyle style) {
        String path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
        String[] segments = path.split("\\.");

        String regexStr = buildJsonPathRegex(segments);
        if (regexStr == null) {
            return json;
        }

        Pattern p = Pattern.compile(regexStr);
        Matcher m = p.matcher(json);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            String masked = maskValue(value, style);
            m.appendReplacement(sb, Matcher.quoteReplacement("\"" + key + "\": \"" + masked + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String buildJsonPathRegex(String[] segments) {
        if (segments.length == 0) return null;

        String lastSegment = segments[segments.length - 1];
        if ("*".equals(lastSegment)) {
            return null;
        }

        String keyPattern = Pattern.quote(lastSegment);
        return "\"(" + keyPattern + ")\"\\s*:\\s*\"([^\"]+)\"";
    }

    static String maskValue(String value, MaskStyle style) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        return switch (style) {
            case FULL -> "***";
            case PARTIAL -> partialMask(value);
            case HASH -> hashMask(value);
        };
    }

    private static String partialMask(String value) {
        if (value.contains("@")) {
            int atIndex = value.indexOf('@');
            if (atIndex <= 2) {
                return "***@" + value.substring(atIndex + 1);
            }
            return value.substring(0, 2) + "***@" + value.substring(atIndex + 1);
        }

        int len = value.length();
        if (len <= 4) {
            return "***" + value.substring(len - 1);
        }
        return value.substring(0, 2) + "***" + value.substring(len - 2);
    }

    private static String hashMask(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = bytesToHex(hash);
            return "sha256:" + hex.substring(0, 12);
        } catch (Exception e) {
            return "***";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * A masking rule configuration.
     */
    public static class Rule {
        public final String patternName;
        public final String jsonPath;
        public final MaskStyle maskStyle;
        public final boolean enabled;

        public Rule(String patternName, String jsonPath, MaskStyle maskStyle, boolean enabled) {
            this.patternName = patternName;
            this.jsonPath = jsonPath;
            this.maskStyle = maskStyle;
            this.enabled = enabled;
        }
    }

    /**
     * Represents a detected PII field.
     */
    public static class PiiMatch {
        public final String patternName;
        public final String fieldName;
        public final String value;

        public PiiMatch(String patternName, String fieldName, String value) {
            this.patternName = patternName;
            this.fieldName = fieldName;
            this.value = value;
        }
    }
}
