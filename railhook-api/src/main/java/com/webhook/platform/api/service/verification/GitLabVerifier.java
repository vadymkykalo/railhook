package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * GitLab webhook verifier.
 *
 * <p>GitLab does not sign the body. It sends the shared secret back verbatim:
 *
 * <pre>X-Gitlab-Token: &lt;the secret token configured on the webhook&gt;</pre>
 *
 * <p>{@code ProviderType.GITLAB} used to be routed to {@link GitHubVerifier}, which looks for
 * {@code X-Hub-Signature-256}. GitLab never sends that header, so every GitLab webhook failed
 * with "Missing header: X-Hub-Signature-256" — the provider was listed as supported and could
 * not verify a single delivery. Nor is the generic HMAC path a workaround: there is no HMAC
 * to compute, only a token to compare.
 */
public class GitLabVerifier implements WebhookVerificationStrategy {

    private static final String TOKEN_HEADER = "X-Gitlab-Token";

    /**
     * Unique per event, and the only thing on a GitLab request that is.
     *
     * <p>Replay detection keys off whatever a verifier returns, so returning the token — which
     * is identical on every request by design — would flag the second webhook GitLab ever sent
     * as a replay of the first. When the header is absent (older GitLab, or a system hook) the
     * key is null and replay detection simply does not run, which is the honest outcome: there
     * is nothing on the request to distinguish two identical deliveries.
     */
    private static final String EVENT_UUID_HEADER = "X-Gitlab-Event-UUID";

    @Override
    public VerificationResult verify(String secret, String body, HttpServletRequest request) {
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
