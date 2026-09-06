package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Strategy interface for verifying incoming webhook signatures.
 * Each provider (GitHub, Stripe, Slack, etc.) implements its own verification logic.
 */
public interface WebhookVerificationStrategy {

    /**
     * Verify the webhook signature.
     *
     * @param secret  the decrypted HMAC secret
     * @param body    the raw request body
     * @param request the HTTP servlet request (for accessing headers)
     * @return verification result with success/failure and optional error message
     */
    VerificationResult verify(String secret, String body, HttpServletRequest request);

    record VerificationResult(boolean verified, String error, String replayKey) {
        public static VerificationResult success(String replayKey) {
            return new VerificationResult(true, null, replayKey);
        }

        public static VerificationResult success() {
            return new VerificationResult(true, null, null);
        }

        public static VerificationResult failure(String error) {
            return new VerificationResult(false, error, null);
        }
    }
}
