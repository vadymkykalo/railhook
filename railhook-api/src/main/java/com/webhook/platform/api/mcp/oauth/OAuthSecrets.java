package com.webhook.platform.api.mcp.oauth;

import com.webhook.platform.common.util.CryptoUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Minting, hashing and checking the opaque strings the authorization server hands out.
 *
 * <p>Every kind carries its own prefix, so a leaked string says what it is — and so the
 * {@code /mcp} filter can tell an access token from an API key without a database read.
 */
public final class OAuthSecrets {

    public static final String ACCESS_TOKEN_PREFIX = "rhat_";
    public static final String REFRESH_TOKEN_PREFIX = "rhrt_";
    public static final String CODE_PREFIX = "rhac_";
    public static final String CLIENT_ID_PREFIX = "rhc_";
    public static final String CLIENT_SECRET_PREFIX = "rhcs_";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private OAuthSecrets() {
    }

    /** A prefix and 256 bits of randomness. */
    public static String mint(String prefix) {
        return mint(prefix, 32);
    }

    public static String mint(String prefix, int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return prefix + URL.encodeToString(random);
    }

    /** SHA-256, the same digest API keys are stored under. The values are random, so no salt. */
    public static String hash(String value) {
        return CryptoUtils.hashApiKey(value);
    }

    public static boolean matches(String presented, String storedHash) {
        if (presented == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(presented).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * PKCE S256 (RFC 7636 §4.6): the verifier must hash to the challenge the authorization request
     * carried. The verifier's own shape is checked first — 43 to 128 unreserved characters — so a
     * degenerate one cannot be used to meet a challenge by accident.
     */
    public static boolean pkceMatches(String verifier, String challenge) {
        if (verifier == null || challenge == null || !verifier.matches("[A-Za-z0-9\\-._~]{43,128}")) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return MessageDigest.isEqual(URL.encodeToString(digest).getBytes(StandardCharsets.US_ASCII),
                    challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** A S256 challenge is a base64url SHA-256: exactly 43 characters of that alphabet. */
    public static boolean isS256Challenge(String challenge) {
        return challenge != null && challenge.matches("[A-Za-z0-9\\-_]{43}");
    }
}
