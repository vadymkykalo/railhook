package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * HubSpot webhook signature verifier, version 3 of HubSpot's scheme.
 *
 * <p>HubSpot sends {@code X-HubSpot-Signature-v3}: base64(HMAC-SHA256(clientSecret, data)), over
 * the request method, the full request URI, the raw body and the timestamp, concatenated in that
 * order with no separators. {@code X-HubSpot-Request-Timestamp} carries that timestamp in
 * milliseconds, and HubSpot's own instruction is to refuse anything older than five minutes —
 * which is the difference between v3 and the v1 and v2 schemes it replaced, neither of which
 * bound a request to a moment at all. Only v3 is implemented; the older two exist, and choosing
 * them would mean accepting a webhook that can be captured and replayed for as long as the
 * client secret lives.
 *
 * <p>The URI is the full URL, scheme and host included, so it has the problem
 * {@link TwilioVerifier} and {@link SquareVerifier} have: reading it off the request would mean
 * trusting {@code Host} and {@code X-Forwarded-Proto}, and behind a reverse proxy that is exactly
 * how it breaks. It is rebuilt from {@code webhook.ingress-base-url}, the setting that produces
 * the ingress URL a person pastes into HubSpot.
 *
 * <p>HubSpot decodes a fixed set of percent-escapes in the query string — and only there, never
 * in the path — before signing. A Railhook ingress URL usually has no query at all, but a source
 * behind a rewrite can, and without this the signature would never match.
 *
 * <p>No provider event id is read from a HubSpot request. The body is an array of events, each
 * with its own {@code eventId}; there is no id for the batch. Deduplicating on the first event's
 * id would silently drop the rest of a batch HubSpot had re-cut on a retry, so the handler
 * deduplicates per event instead, and the five-minute window plus
 * {@code ReplayDetectionService} is what bounds a replay here.
 */
public class HubSpotVerifier implements WebhookVerificationStrategy {

    private static final String SIGNATURE_HEADER = "X-HubSpot-Signature-v3";
    private static final String TIMESTAMP_HEADER = "X-HubSpot-Request-Timestamp";
    private static final long TOLERANCE_MILLIS = 300_000L;

    /** The escapes HubSpot turns back into their characters, in the query string only. */
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
            // method + URI, then the body's own bytes, then the timestamp: the body sits in the
            // middle of the signed string and is fed in without being decoded and re-encoded.
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
