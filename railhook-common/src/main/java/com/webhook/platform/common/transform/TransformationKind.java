package com.webhook.platform.common.transform;

import java.util.Locale;

public enum TransformationKind {

    /** The original language and the default. Existing rows are never migrated. */
    TEMPLATE,

    JAVASCRIPT;

    /** Anything unrecognised, including null, is TEMPLATE. */
    public static TransformationKind fromStored(String value) {
        if (value == null || value.isBlank()) {
            return TEMPLATE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TEMPLATE;
        }
    }
}
