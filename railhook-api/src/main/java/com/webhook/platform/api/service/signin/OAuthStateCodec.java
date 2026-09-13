package com.webhook.platform.api.service.signin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps a sign-in's {@link OAuthState} in the browser, signed, instead of on the server.
 *
 * <p>A cookie rather than Redis because the state has exactly one reader — the callback in the
 * same browser — and a server-side store would add a second system to the one path that must
 * work when a person is locked out of everything else. The cookie is HttpOnly and SameSite=Lax,
 * which is what lets it ride along on Google's top-level redirect back and nowhere else.
 *
 * <p>The key is derived from the JWT secret with a label, so the two uses never share a key
 * even though operators configure one secret.
 */
public class OAuthStateCodec {

    public static final Duration LIFETIME = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OAuthStateCodec(String secret, Clock clock) {
        this.key = sha256(("railhook-oauth-state:" + secret).getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public OAuthState newState(String returnTo, String intent) {
        return new OAuthState(
                random(32),
                random(32),
                // 48 bytes is 64 characters, inside RFC 7636's 43..128.
                random(48),
                SafeReturnPath.of(returnTo),
                "register".equals(intent) ? "register" : "login",
                clock.instant().plus(LIFETIME).truncatedTo(ChronoUnit.SECONDS));
    }

    public String encode(OAuthState state) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("s", state.state());
        fields.put("n", state.nonce());
        fields.put("v", state.codeVerifier());
        fields.put("r", state.returnTo());
        fields.put("i", state.intent());
        fields.put("e", state.expiresAt().getEpochSecond());
        try {
            String payload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(fields));
            return payload + "." + ENCODER.encodeToString(hmac(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode the sign-in state", e);
        }
    }

    /** The state, if the cookie is one this codec signed and it has not expired; empty otherwise. */
    public Optional<OAuthState> decode(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return Optional.empty();
        }
        int dot = cookie.indexOf('.');
        if (dot <= 0 || dot != cookie.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            String payload = cookie.substring(0, dot);
            byte[] signature = DECODER.decode(cookie.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(payload), signature)) {
                return Optional.empty();
            }
            JsonNode fields = objectMapper.readTree(DECODER.decode(payload));
            Instant expiresAt = Instant.ofEpochSecond(fields.path("e").asLong(0));
            if (!expiresAt.isAfter(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(new OAuthState(
                    fields.path("s").asText(),
                    fields.path("n").asText(),
                    fields.path("v").asText(),
                    SafeReturnPath.of(fields.path("r").asText(null)),
                    fields.path("i").asText("login"),
                    expiresAt));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return ENCODER.encodeToString(value);
    }
}
