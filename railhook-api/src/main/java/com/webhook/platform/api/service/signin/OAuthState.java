package com.webhook.platform.api.service.signin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * One sign-in in flight: what Google must echo back ({@code state}), what its ID token must carry
 * ({@code nonce}), the PKCE secret the code exchange proves possession of, and where the person
 * was going.
 *
 * @param intent {@code login} or {@code register} — which page an error returns to; both sign in
 *               an existing account and create a missing one
 */
public record OAuthState(String state, String nonce, String codeVerifier, String returnTo, String intent,
                         Instant expiresAt) {

    /** RFC 7636 S256: the challenge sent to Google, from which the verifier cannot be recovered. */
    public String codeChallenge() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
