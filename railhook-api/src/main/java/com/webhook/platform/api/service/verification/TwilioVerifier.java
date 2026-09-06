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

/**
 * Twilio webhook signature verifier.
 *
 * <p>Twilio signs {@code X-Twilio-Signature} as base64(HMAC-SHA1(authToken, data)), and what goes
 * into {@code data} depends on how the request was encoded:
 *
 * <ul>
 *   <li><b>form-encoded</b> (the classic messaging and voice callbacks) — the full request URL,
 *       then every POST parameter sorted by name, each written as the name immediately followed
 *       by its decoded value, with no separators;
 *   <li><b>anything else</b> (JSON, from the newer webhooks) — the full request URL alone, with
 *       Twilio having appended {@code bodySHA256=<hex>} to its query string. The signature then
 *       covers the body only through that hash, so this verifier checks the hash as well: without
 *       it the URL signature would keep verifying while somebody swapped the body underneath it.
 * </ul>
 *
 * <p>"The full request URL" is the one Twilio was configured with, and getting it from the
 * incoming request would mean trusting {@code Host} and {@code X-Forwarded-Proto} — behind a
 * reverse proxy that is exactly how Twilio verification usually breaks. It comes from
 * {@code webhook.ingress-base-url} instead: the same setting that builds the ingress URL shown on
 * the source's page, which is the URL a person copies into the Twilio console. The two agree by
 * construction. Only when it is unset does this fall back to what the request claims.
 */
public class TwilioVerifier implements WebhookVerificationStrategy {

    private static final String SIGNATURE_HEADER = "X-Twilio-Signature";
    private static final String BODY_HASH_PARAM = "bodySHA256";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private final String ingressBaseUrl;

    public TwilioVerifier(String ingressBaseUrl) {
        this.ingressBaseUrl = ingressBaseUrl;
    }

    @Override
    public VerificationResult verify(String secret, String body, HttpServletRequest request) {
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

    /** Sorted by parameter name, each name immediately followed by its decoded value. */
    private static String concatenatedParameters(String body) {
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

    private static String sha256Hex(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]));
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
