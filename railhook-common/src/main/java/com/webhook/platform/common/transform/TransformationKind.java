package com.webhook.platform.common.transform;

/**
 * The language a Transformation is written in.
 *
 * <p>{@link #TEMPLATE} is the original one and stays the default: a JSON document whose string
 * values may hold {@code ${$.jsonpath}} expressions. Every row that existed before this enum did
 * is a TEMPLATE, and nothing migrates them — a template that works is not a problem to be fixed.
 */
public enum TransformationKind {

    /** A JSON document with {@code ${$.jsonpath}} substitutions. Cannot loop, branch or compute. */
    TEMPLATE,

    /** A JavaScript {@code handler(webhook)} run in a sandbox. See {@code JavaScriptTransformEngine}. */
    JAVASCRIPT;

    /** Parses a stored value, treating anything unrecognised — including null — as TEMPLATE. */
    public static TransformationKind fromStored(String value) {
        if (value == null || value.isBlank()) {
            return TEMPLATE;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TEMPLATE;
        }
    }
}
