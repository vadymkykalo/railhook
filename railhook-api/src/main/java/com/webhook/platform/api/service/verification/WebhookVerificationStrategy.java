package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

public interface WebhookVerificationStrategy {

    /** {@code body} is the raw bytes the sender signed; re-encoding them broke genuine signatures. */
    VerificationResult verify(String secret, byte[] body, HttpServletRequest request);

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
