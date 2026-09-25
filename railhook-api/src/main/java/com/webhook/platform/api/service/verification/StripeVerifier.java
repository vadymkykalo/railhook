package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Stripe-Signature: t=timestamp,v1=hex HMAC-SHA256 of "timestamp.body". */
public class StripeVerifier implements WebhookVerificationStrategy {

    private static final String HEADER = "Stripe-Signature";
    private static final long TOLERANCE_SECONDS = 300;

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return VerificationResult.failure("Missing header: " + HEADER);
        }

        String timestamp = null;
        // Every v1, not the last: during a secret roll Stripe sends one per live secret, in no
        // particular order.
        List<String> signatures = new ArrayList<>();

        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                if ("t".equals(kv[0])) {
                    timestamp = kv[1];
                } else if ("v1".equals(kv[0])) {
                    signatures.add(kv[1]);
                }
            }
        }

        if (timestamp == null || signatures.isEmpty()) {
            return VerificationResult.failure("Invalid Stripe-Signature format: missing t or v1");
        }

        try {
            long ts = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > TOLERANCE_SECONDS) {
                return VerificationResult.failure("Stripe timestamp outside tolerance window (" + TOLERANCE_SECONDS + "s)");
            }
        } catch (NumberFormatException e) {
            return VerificationResult.failure("Invalid Stripe timestamp: " + timestamp);
        }

        // Joined as bytes so the body is not re-encoded.
        byte[] computed = GenericHmacVerifier.computeHmacSha256(secret, timestamp + ".", body)
                .getBytes(StandardCharsets.UTF_8);

        // No early exit, so timing does not reveal which candidate matched.
        boolean valid = false;
        for (String signature : signatures) {
            valid |= MessageDigest.isEqual(computed, signature.getBytes(StandardCharsets.UTF_8));
        }
        return valid ? VerificationResult.success(header) : VerificationResult.failure("Stripe signature mismatch");
    }
}
