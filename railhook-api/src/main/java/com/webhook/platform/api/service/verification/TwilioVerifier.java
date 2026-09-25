package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

// Non-form requests sign only the URL, so its bodySHA256 must be checked or the body could be
// swapped. The URL comes from ingress-base-url, not Host headers, which break behind a proxy.
public class TwilioVerifier implements WebhookVerificationStrategy {

    private static final String SIGNATURE_HEADER = "X-Twilio-Signature";
    private static final String BODY_HASH_PARAM = "bodySHA256";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private final String ingressBaseUrl;

    public TwilioVerifier(String ingressBaseUrl) {
        this.ingressBaseUrl = ingressBaseUrl;
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (signature == null || signature.isBlank()) {
            return VerificationResult.failure("Missing header: " + SIGNATURE_HEADER);
        }

        String url = signedUrl(request);
        String contentType = request.getContentType();
        boolean formEncoded = contentType != null
                && contentType.toLowerCase().startsWith(FORM_CONTENT_TYPE);

        String data;
        if (formEncoded) {
            data = url + concatenatedParameters(body);
        } else {
            String expectedHash = queryParameter(request.getQueryString(), BODY_HASH_PARAM);
            if (expectedHash == null) {
                return VerificationResult.failure(
                        "Twilio request has no " + BODY_HASH_PARAM + " query parameter and is not "
                                + "form-encoded, so its signature covers nothing of the body");
            }
            if (!MessageDigest.isEqual(sha256Hex(body).getBytes(StandardCharsets.UTF_8),
                    expectedHash.getBytes(StandardCharsets.UTF_8))) {
                return VerificationResult.failure("Twilio " + BODY_HASH_PARAM + " does not match the body");
            }
            data = url;
        }

        String computed = hmacSha1Base64(secret, data);
        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return valid ? VerificationResult.success(signature) : VerificationResult.failure("Twilio signature mismatch");
    }

    private String signedUrl(HttpServletRequest request) {
        String base = ingressBaseUrl != null && !ingressBaseUrl.isBlank()
                ? stripTrailingSlash(ingressBaseUrl) + request.getRequestURI()
                : request.getRequestURL().toString();
        String query = request.getQueryString();
        return query != null && !query.isBlank() ? base + "?" + query : base;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    // A form-encoded body is ASCII by definition, so decoding it as UTF-8 is safe.
    private static String concatenatedParameters(byte[] rawBody) {
        String body = rawBody != null ? new String(rawBody, StandardCharsets.UTF_8) : null;
        Map<String, String> sorted = new TreeMap<>();
        if (body != null && !body.isBlank()) {
            for (String pair : body.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = eq >= 0 ? pair.substring(0, eq) : pair;
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                sorted.put(urlDecode(name), urlDecode(value));
            }
        }
        StringBuilder sb = new StringBuilder();
        sorted.forEach((name, value) -> sb.append(name).append(value));
        return sb.toString();
    }

    private static String queryParameter(String queryString, String name) {
        if (queryString == null || queryString.isBlank()) {
            return null;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && urlDecode(pair.substring(0, eq)).equals(name)) {
                return urlDecode(pair.substring(eq + 1));
            }
        }
        return null;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body != null ? body : new byte[0]));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256", e);
        }
    }

    private static String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA1", e);
        }
    }
}
