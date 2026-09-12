package com.webhook.platform.api.service.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
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

    public static boolean isSensitiveHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
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

    public static Map<String, String> sanitize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (isSensitiveHeader(entry.getKey())) {
                result.put(entry.getKey(), MASKED_VALUE);
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public static String maskSignature(String signature) {
        if (signature == null || signature.length() <= 8) {
            return MASKED_VALUE;
        }
        return "sig_..." + signature.substring(signature.length() - 8);
    }

    public static String toJson(HttpServletRequest request, ObjectMapper objectMapper) {
        try {
            Map<String, String> headers = new HashMap<>();
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                String value = isSensitiveHeader(name)
                        ? MASKED_VALUE
                        : request.getHeader(name);
                headers.put(name, value);
            }
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            log.warn("Failed to serialize request headers: {}", e.getMessage());
            return "{}";
        }
    }
}
