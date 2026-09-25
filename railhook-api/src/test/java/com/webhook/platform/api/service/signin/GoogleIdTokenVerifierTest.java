package com.webhook.platform.api.service.signin;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Every check Google documents for an ID token is a way into someone else's account when skipped.
class GoogleIdTokenVerifierTest {

    private static final String CLIENT_ID = "client-123.apps.googleusercontent.com";
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private static final KeyPair GOOGLE_KEYS = rsa();
    private static final KeyPair SOMEONE_ELSES_KEYS = rsa();

    private final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(
            () -> jwks("k1", (RSAPublicKey) GOOGLE_KEYS.getPublic()), CLIENT_ID, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsATokenGoogleIssuedForThisClient() {
        VerifiedIdentity identity = verifier.verify(token(Map.of()), "nonce-1");

        assertThat(identity.provider()).isEqualTo("google");
        assertThat(identity.subject()).isEqualTo("107691503500061507151");
        assertThat(identity.email()).isEqualTo("ada@acme.com");
        assertThat(identity.fullName()).isEqualTo("Ada Lovelace");
        assertThat(identity.givenName()).isEqualTo("Ada");
        assertThat(identity.hostedDomain()).isEqualTo("acme.com");
    }

    @Test
    void acceptsTheIssuerWrittenWithoutAScheme() {
        // Google documents both spellings as valid.
        assertThat(verifier.verify(token(Map.of("iss", "accounts.google.com")), "nonce-1").email())
                .isEqualTo("ada@acme.com");
    }

    @Test
    void acceptsEmailVerifiedWrittenAsAString() {
        assertThat(verifier.verify(token(Map.of("email_verified", "true")), "nonce-1").email())
                .isEqualTo("ada@acme.com");
    }

    @Test
    void refusesATokenIssuedForAnotherClient() {
        // Any site using Google sign-in can obtain a genuine token for a visitor; accepting one
        // minted for somebody else's client lets that site sign in here as the visitor.
        assertIdentityRejected(token(Map.of("aud", "another-app.apps.googleusercontent.com")), "nonce-1");
    }

    @Test
    void refusesATokenFromAnotherIssuer() {
        assertIdentityRejected(token(Map.of("iss", "https://accounts.example.com")), "nonce-1");
    }

    @Test
    void refusesATokenCarryingAnotherSignInsNonce() {
        assertIdentityRejected(token(Map.of()), "nonce-from-a-different-sign-in");
    }

    @Test
    void refusesAnExpiredToken() {
        assertIdentityRejected(token(Map.of("exp", NOW.minusSeconds(600).getEpochSecond())), "nonce-1");
    }

    @Test
    void refusesATokenSignedWithAKeyGoogleDoesNotPublish() {
        assertIdentityRejected(sign(claims(Map.of()), "k1", SOMEONE_ELSES_KEYS.getPrivate()), "nonce-1");
    }

    @Test
    void refusesATokenNamingAnUnknownKey() {
        assertIdentityRejected(sign(claims(Map.of()), "k-unknown", GOOGLE_KEYS.getPrivate()), "nonce-1");
    }

    @Test
    void refusesASymmetricallySignedToken() {
        // Signed with HS256 under a key id Google publishes: the classic confusion where a verifier
        // treats a public key as an HMAC secret.
        String hs256 = Jwts.builder().header().keyId("k1").and()
                .claims(claims(Map.of()))
                .signWith(Keys.hmacShaKeyFor("a-thirty-two-byte-long-hmac-key!".getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
        assertIdentityRejected(hs256, "nonce-1");
    }

    @Test
    void refusesAnAddressGoogleHasNotVerified() {
        assertThatThrownBy(() -> verifier.verify(token(Map.of("email_verified", false)), "nonce-1"))
                .isInstanceOf(SignInRejectedException.class)
                .extracting(e -> ((SignInRejectedException) e).failure())
                .isEqualTo(SignInFailure.UNVERIFIED_EMAIL);
    }

    @Test
    void refusesATokenWithNoAddress() {
        Map<String, Object> noEmail = claims(Map.of());
        noEmail.remove("email");
        assertIdentityRejected(sign(noEmail, "k1", GOOGLE_KEYS.getPrivate()), "nonce-1");
    }

    private void assertIdentityRejected(String idToken, String nonce) {
        assertThatThrownBy(() -> verifier.verify(idToken, nonce))
                .isInstanceOf(SignInRejectedException.class)
                .extracting(e -> ((SignInRejectedException) e).failure())
                .isEqualTo(SignInFailure.IDENTITY);
    }

    private static String token(Map<String, Object> overrides) {
        return sign(claims(overrides), "k1", GOOGLE_KEYS.getPrivate());
    }

    private static Map<String, Object> claims(Map<String, Object> overrides) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", CLIENT_ID);
        claims.put("sub", "107691503500061507151");
        claims.put("email", "ada@acme.com");
        claims.put("email_verified", true);
        claims.put("name", "Ada Lovelace");
        claims.put("given_name", "Ada");
        claims.put("hd", "acme.com");
        claims.put("nonce", "nonce-1");
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(3600).getEpochSecond());
        claims.putAll(overrides);
        return claims;
    }

    static String sign(Map<String, Object> claims, String keyId, PrivateKey key) {
        return Jwts.builder().header().keyId(keyId).and()
                .claims(claims)
                .signWith(key, Jwts.SIG.RS256)
                .compact();
    }

    static String jwks(String keyId, RSAPublicKey key) {
        return "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"" + keyId
                + "\",\"n\":\"" + base64Url(key.getModulus()) + "\",\"e\":\"" + base64Url(key.getPublicExponent())
                + "\"}]}";
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
