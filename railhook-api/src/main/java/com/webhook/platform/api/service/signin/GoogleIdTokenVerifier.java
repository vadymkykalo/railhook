package com.webhook.platform.api.service.signin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Checks a Google ID token the way Google's documentation says to, and nothing looser.
 *
 * <p>Each check closes a specific way into somebody else's account: the signature against Google's
 * published keys (a token anyone could write), the audience (a genuine token Google issued to a
 * different site, which that site can replay here), the nonce (a token captured from an earlier
 * sign-in), the expiry, and {@code email_verified} (an address nobody proved they own). RS256 is
 * the only algorithm accepted, so a token cannot choose to be checked as HMAC against a public key.
 *
 * <p>Keys are fetched from the JWKS URL and cached for an hour. An unknown key id refetches once a
 * minute at most, which follows Google's rotations without letting junk tokens hammer the endpoint.
 */
public class GoogleIdTokenVerifier {

    /** Where the JSON Web Key Set comes from; Google's certs URL in production. */
    @FunctionalInterface
    public interface KeySetSource {
        String fetch();
    }

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final Duration KEYS_MAX_AGE = Duration.ofHours(1);
    private static final Duration REFETCH_FLOOR = Duration.ofMinutes(1);
    private static final long CLOCK_SKEW_SECONDS = 60;

    private final KeySetSource keySetSource;
    private final String clientId;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    public GoogleIdTokenVerifier(KeySetSource keySetSource, String clientId, Clock clock) {
        this.keySetSource = keySetSource;
        this.clientId = clientId;
        this.clock = clock;
    }

    public VerifiedIdentity verify(String idToken, String expectedNonce) {
        try {
            Claims claims = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            if (!"RS256".equals(header.getAlgorithm())) {
                                throw rejected("unexpected signing algorithm " + header.getAlgorithm());
                            }
                            return keyFor(header.getKeyId());
                        }
                    })
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();

            if (!ISSUERS.contains(claims.getIssuer())) {
                throw rejected("issuer " + claims.getIssuer());
            }
            Set<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(clientId)) {
                throw rejected("token issued for another client");
            }
            String nonce = claims.get("nonce", String.class);
            if (nonce == null || expectedNonce == null || !MessageDigest.isEqual(
                    nonce.getBytes(StandardCharsets.UTF_8), expectedNonce.getBytes(StandardCharsets.UTF_8))) {
                throw rejected("nonce does not match this sign-in");
            }
            String subject = claims.getSubject();
            String email = claims.get("email", String.class);
            if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
                throw rejected("token names no subject or address");
            }
            Object verified = claims.get("email_verified");
            if (!(Boolean.TRUE.equals(verified) || "true".equals(verified))) {
                throw new SignInRejectedException(SignInFailure.UNVERIFIED_EMAIL, "Google has not verified " + email);
            }
            return new VerifiedIdentity("google", subject, email,
                    claims.get("name", String.class),
                    claims.get("given_name", String.class),
                    claims.get("hd", String.class));
        } catch (SignInRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            // Every JwtException, a malformed token, a claim of the wrong type: all mean the same thing.
            if (e.getCause() instanceof SignInRejectedException rejected) {
                throw rejected;
            }
            throw rejected(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Key keyFor(String keyId) {
        if (keyId == null) {
            throw rejected("token names no key");
        }
        Instant now = clock.instant();
        PublicKey key = keys.get(keyId);
        boolean stale = fetchedAt.plus(KEYS_MAX_AGE).isBefore(now);
        boolean mayRefetch = keys.isEmpty() || fetchedAt.plus(REFETCH_FLOOR).isBefore(now);
        if ((key == null || stale) && mayRefetch) {
            refresh(now);
            key = keys.get(keyId);
        }
        if (key == null) {
            throw rejected("no published key " + keyId);
        }
        return key;
    }

    private synchronized void refresh(Instant now) {
        String json;
        try {
            json = keySetSource.fetch();
        } catch (RuntimeException e) {
            throw new SignInRejectedException(SignInFailure.UNAVAILABLE, "could not fetch Google's keys: " + e.getMessage());
        }
        try {
            Map<String, PublicKey> parsed = new HashMap<>();
            KeyFactory factory = KeyFactory.getInstance("RSA");
            for (JsonNode jwk : objectMapper.readTree(json).path("keys")) {
                if (!"RSA".equals(jwk.path("kty").asText()) || jwk.path("kid").isMissingNode()) {
                    continue;
                }
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
                parsed.put(jwk.path("kid").asText(), factory.generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            }
            keys = Map.copyOf(parsed);
            fetchedAt = now;
        } catch (Exception e) {
            throw new SignInRejectedException(SignInFailure.UNAVAILABLE, "Google's key set could not be read: " + e.getMessage());
        }
    }

    private static SignInRejectedException rejected(String why) {
        return new SignInRejectedException(SignInFailure.IDENTITY, why);
    }
}
