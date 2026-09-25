package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** GitLab does not sign the body; it sends the shared secret back verbatim in X-Gitlab-Token. */
public class GitLabVerifier implements WebhookVerificationStrategy {

    private static final String TOKEN_HEADER = "X-Gitlab-Token";

    // The replay key: the token is identical on every request.
    private static final String EVENT_UUID_HEADER = "X-Gitlab-Event-UUID";

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return VerificationResult.failure("Missing header: " + TOKEN_HEADER);
        }
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failure("No secret token configured for this source");
        }

        boolean valid = MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            return VerificationResult.failure("GitLab token mismatch");
        }

        String eventUuid = request.getHeader(EVENT_UUID_HEADER);
        return eventUuid != null && !eventUuid.isBlank()
                ? VerificationResult.success(eventUuid)
                : VerificationResult.success();
    }
}
