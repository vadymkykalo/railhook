package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Signature v3 only, since v1 and v2 carry no timestamp. The signed URI is rebuilt from the
 * ingress base URL rather than trusting Host headers behind a proxy.
 */
public class HubSpotVerifier implements WebhookVerificationStrategy {

    private static final String SIGNATURE_HEADER = "X-HubSpot-Signature-v3";
    private static final String TIMESTAMP_HEADER = "X-HubSpot-Request-Timestamp";
    private static final long TOLERANCE_MILLIS = 300_000L;

    // HubSpot decodes these before signing, in the query string only, never the path.
    private static final Map<String, String> QUERY_ESCAPES = Map.ofEntries(
            Map.entry("%3A", ":"), Map.entry("%2F", "/"), Map.entry("%3F", "?"),
            Map.entry("%40", "@"), Map.entry("%21", "!"), Map.entry("%24", "$"),
            Map.entry("%27", "'"), Map.entry("%28", "("), Map.entry("%29", ")"),
            Map.entry("%2A", "*"), Map.entry("%2C", ","), Map.entry("%3B", ";"));

    private final String ingressBaseUrl;

    public HubSpotVerifier(String ingressBaseUrl) {
        this.ingressBaseUrl = ingressBaseUrl;
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (signature == null || signature.isBlank()) {
            return VerificationResult.failure("Missing header: " + SIGNATURE_HEADER);
        }
        String timestampHeader = request.getHeader(TIMESTAMP_HEADER);
        if (timestampHeader == null || timestampHeader.isBlank()) {
            return VerificationResult.failure("Missing header: " + TIMESTAMP_HEADER);
        }
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failure("No HubSpot client secret configured on this source");
        }

        try {
            long timestamp = Long.parseLong(timestampHeader.trim());
            if (Math.abs(System.currentTimeMillis() - timestamp) > TOLERANCE_MILLIS) {
                return VerificationResult.failure(
                        "HubSpot timestamp outside tolerance window (" + TOLERANCE_MILLIS / 1000 + "s)");
            }
        } catch (NumberFormatException e) {
            return VerificationResult.failure("Invalid HubSpot timestamp: " + timestampHeader);
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            // The raw body sits in the middle of the signed string and must not be re-encoded.
            mac.update((request.getMethod() + requestUri(request)).getBytes(StandardCharsets.UTF_8));
            mac.update(body != null ? body : new byte[0]);
            String computed = Base64.getEncoder()
                    .encodeToString(mac.doFinal(timestampHeader.getBytes(StandardCharsets.UTF_8)));

            boolean valid = MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
            return valid ? VerificationResult.success(signature + "|" + timestampHeader)
                    : VerificationResult.failure("HubSpot signature mismatch");
        } catch (Exception e) {
            return VerificationResult.failure("HubSpot verification error: " + e.getMessage());
        }
    }

    private String requestUri(HttpServletRequest request) {
        String base = ingressBaseUrl != null && !ingressBaseUrl.isBlank()
                ? stripTrailingSlash(ingressBaseUrl) + request.getRequestURI()
                : request.getRequestURL().toString();
        String query = request.getQueryString();
        return query != null && !query.isBlank() ? base + "?" + decodeQueryEscapes(query) : base;
    }

    private static String decodeQueryEscapes(String query) {
        String decoded = query;
        for (Map.Entry<String, String> escape : QUERY_ESCAPES.entrySet()) {
            decoded = decoded.replace(escape.getKey(), escape.getValue());
        }
        return decoded;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
