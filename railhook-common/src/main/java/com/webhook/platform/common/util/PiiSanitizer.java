package com.webhook.platform.common.util;

import java.util.ArrayList;
import java.util.List;
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

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\"([^\"]*(?:email|e-mail|mail)[^\"]*?)\"\\s*:\\s*\"([^\"]+@[^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\"([^\"]*(?:phone|mobile|cell|tel|fax)[^\"]*?)\"\\s*:\\s*\"([+]?[0-9\\s\\-().]{7,20})\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "\"([^\"]*(?:card|pan|credit|debit|account)[^\"]*?)\"\\s*:\\s*\"(\\d[\\d\\s\\-]{11,18}\\d)\"",
            Pattern.CASE_INSENSITIVE);

    /**
     * The same PAN, one level down.
     *
     * <p>{@link #CARD_PATTERN} asks the key holding the digits to be card-ish,
     * which is true of {@code "cardNumber"} and {@code "pan"} but not of the
     * shape every payment provider actually sends:
     * {@code {"card": {"number": "4242…"}}}. There the key on the digits is the
     * entirely innocent {@code "number"}, and the only thing saying "card" is
     * the object around it — so a rule an operator had enabled, and which the
     * UI showed as enabled, forwarded the PAN in the clear.
     *
     * <p>The context comes from a lookbehind rather than a wider key list
     * because {@code "number"} on its own is not evidence of anything: an order
     * number of the same length must survive untouched, and it does — nothing
     * matches unless a card-ish key opens the object it sits in. Every quantifier
     * here is bounded, which Java requires of a lookbehind and which also caps
     * how far the context may reach.
     */
    private static final Pattern CARD_OBJECT_PATTERN = Pattern.compile(
            "(?<=\"[^\"]{0,40}(?:card|credit|debit)[^\"]{0,40}\"\\s{0,4}:\\s{0,4}\\{[^{}]{0,300})"
                    + "\"(number|pan|num|value)\"\\s*:\\s*\"(\\d[\\d\\s\\-]{11,18}\\d)\"",
            Pattern.CASE_INSENSITIVE);

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

        detectPattern(json, EMAIL_PATTERN, BUILTIN_EMAIL, matches);
        detectPattern(json, PHONE_PATTERN, BUILTIN_PHONE, matches);
        detectPattern(json, CARD_PATTERN, BUILTIN_CARD, matches);
        detectPattern(json, CARD_OBJECT_PATTERN, BUILTIN_CARD, matches);
        return matches;
    }

    private static void detectPattern(String json, Pattern pattern, String patternName, List<PiiMatch> matches) {
        Matcher m = pattern.matcher(json);
        while (m.find()) {
            matches.add(new PiiMatch(patternName, m.group(1), m.group(2)));
        }
    }

    private static String applyRule(String json, Rule rule) {
        switch (rule.patternName) {
            case BUILTIN_EMAIL:
                return applyBuiltinPattern(json, EMAIL_PATTERN, rule.maskStyle);
            case BUILTIN_PHONE:
                return applyBuiltinPattern(json, PHONE_PATTERN, rule.maskStyle);
            case BUILTIN_CARD:
                // Both shapes, because a payload routinely carries only one of them.
                return applyBuiltinPattern(
                        applyBuiltinPattern(json, CARD_PATTERN, rule.maskStyle),
                        CARD_OBJECT_PATTERN, rule.maskStyle);
            default:
                if (rule.jsonPath != null && !rule.jsonPath.isBlank()) {
                    return applyJsonPathRule(json, rule.jsonPath, rule.maskStyle);
                }
                return json;
        }
    }

    private static String applyBuiltinPattern(String json, Pattern pattern, MaskStyle style) {
        Matcher m = pattern.matcher(json);
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
