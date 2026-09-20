package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Square webhook signature verifier.
 *
 * <p>Square sends {@code x-square-hmacsha256-signature}: base64(HMAC-SHA256(signatureKey, data)),
 * where {@code data} is the subscription's notification URL followed immediately by the raw
 * request body, with no separator between them.
 *
 * <p>The URL being part of the signed data is the whole difficulty. {@code http} for
 * {@code https}, a trailing slash or a different host each change the digest, so the URL has to
 * be exactly the one entered in the Square developer console. Taking it from the incoming
 * request would mean trusting {@code Host} and {@code X-Forwarded-Proto}, which behind a reverse
 * proxy is precisely how this breaks — the same trap {@link TwilioVerifier} documents. It comes
 * from {@code webhook.ingress-base-url} instead: the setting that builds the ingress URL shown on
 * the source's page, which is the URL a person copies into Square. The two agree by construction.
 * Only when it is unset does this fall back to what the request claims.
 *
 * <p>Square's scheme carries no timestamp, so the signature over an unchanged body is stable and
 * a resend arrives with the signature already marked as seen. What separates a resend from a
 * replay is the notification's own {@code event_id}, which {@code ProviderEventIdExtractor} reads
 * out of the body and {@code IngressService} deduplicates on ahead of the replay check.
 */
public class SquareVerifier implements WebhookVerificationStrategy {

    private static final String HEADER = "x-square-hmacsha256-signature";

    private final String ingressBaseUrl;

    public SquareVerifier(String ingressBaseUrl) {
        this.ingressBaseUrl = ingressBaseUrl;
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String signature = request.getHeader(HEADER);
        if (signature == null || signature.isBlank()) {
            return VerificationResult.failure("Missing header: " + HEADER);
        }
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failure("No Square signature key configured on this source");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            // The URL is ASCII and the body is whatever Square sent, so the two are joined as
            // bytes: nothing re-encodes the body on its way into the digest.
            mac.update(notificationUrl(request).getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(mac.doFinal(body != null ? body : new byte[0]));

            boolean valid = MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
            return valid ? VerificationResult.success(signature)
                    : VerificationResult.failure("Square signature mismatch");
        } catch (Exception e) {
            return VerificationResult.failure("Square verification error: " + e.getMessage());
        }
    }

    /** The notification URL as Square was configured with it, query string included. */
    private String notificationUrl(HttpServletRequest request) {
        String base = ingressBaseUrl != null && !ingressBaseUrl.isBlank()
                ? stripTrailingSlash(ingressBaseUrl) + request.getRequestURI()
                : request.getRequestURL().toString();
        String query = request.getQueryString();
        return query != null && !query.isBlank() ? base + "?" + query : base;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
