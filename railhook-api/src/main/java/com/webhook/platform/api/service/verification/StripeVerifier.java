package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Stripe webhook signature verifier.
 * Stripe sends: Stripe-Signature: t=<timestamp>,v1=<hex-hmac-sha256>
 * Signed payload = "<timestamp>.<body>"
 * Tolerance: 5 minutes (300 seconds).
 */
public class StripeVerifier implements WebhookVerificationStrategy {

    private static final String HEADER = "Stripe-Signature";
    private static final long TOLERANCE_SECONDS = 300;

    @Override
    public VerificationResult verify(String secret, String body, HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return VerificationResult.failure("Missing header: " + HEADER);
        }

        String timestamp = null;
        String signature = null;

        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                if ("t".equals(kv[0])) {
                    timestamp = kv[1];
                } else if ("v1".equals(kv[0])) {
                    signature = kv[1];
                }
            }
        }

        if (timestamp == null || signature == null) {
            return VerificationResult.failure("Invalid Stripe-Signature format: missing t or v1");
        }

        // Timestamp tolerance check
        try {
            long ts = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > TOLERANCE_SECONDS) {
                return VerificationResult.failure("Stripe timestamp outside tolerance window (" + TOLERANCE_SECONDS + "s)");
            }
        } catch (NumberFormatException e) {
            return VerificationResult.failure("Invalid Stripe timestamp: " + timestamp);
        }

        // Stripe signs: "<timestamp>.<body>"
        String signedPayload = timestamp + "." + (body != null ? body : "");
        String computed = GenericHmacVerifier.computeHmacSha256(secret, signedPayload);

        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return valid ? VerificationResult.success(header) : VerificationResult.failure("Stripe signature mismatch");
    }
}
