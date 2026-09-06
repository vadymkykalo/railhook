package com.webhook.platform.common.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility for masking sensitive HTTP header values before storage.
 * Works with plain Maps — no servlet dependencies.
 */
public final class HeaderSanitizer {

    private static final Set<String> SENSITIVE_HEADERS_EXACT = Set.of(
            "authorization", "cookie", "set-cookie",
            "x-api-key", "proxy-authorization"
    );
    private static final List<String> SENSITIVE_HEADER_PATTERNS = List.of(
            "signature", "token", "secret", "hmac", "auth", "key", "credential", "password"
    );
    private static final String MASKED_VALUE = "***MASKED***";

    private HeaderSanitizer() {
    }

    /**
     * Returns true if the header name matches known sensitive patterns.
     */
    public static boolean isSensitiveHeader(String headerName) {
        if (headerName == null) return false;
        String lower = headerName.toLowerCase();
        if (SENSITIVE_HEADERS_EXACT.contains(lower)) {
            return true;
        }
        for (String pattern : SENSITIVE_HEADER_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes a map of headers, masking values of sensitive headers.
     */
    public static Map<String, String> sanitize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (isSensitiveHeader(entry.getKey())) {
                result.put(entry.getKey(), MASKED_VALUE);
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Masks a signature value, keeping only the last 8 characters for debugging.
     * Example: "v1=abc123...xyz789" → "sig_...xyz789**"
     */
    public static String maskSignature(String signature) {
        if (signature == null || signature.length() <= 8) {
            return MASKED_VALUE;
        }
        return "sig_..." + signature.substring(signature.length() - 8);
    }
}
