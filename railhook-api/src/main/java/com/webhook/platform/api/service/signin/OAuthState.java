package com.webhook.platform.api.service.signin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/** @param intent {@code login} or {@code register}; only decides which page an error returns to */
public record OAuthState(String state, String nonce, String codeVerifier, String returnTo, String intent,
                         Instant expiresAt) {

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
