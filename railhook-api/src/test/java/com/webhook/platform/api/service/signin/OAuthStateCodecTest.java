package com.webhook.platform.api.service.signin;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

// The signed cookie's signature and expiry are all that stop a forged or replayed callback.
class OAuthStateCodecTest {

    private static final String SECRET = "test_jwt_secret_key_minimum_32_chars_required_here";
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    private static OAuthStateCodec codecAt(Instant instant, String secret) {
        return new OAuthStateCodec(secret, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void readsBackWhatItWrote() {
        OAuthStateCodec codec = codecAt(NOW, SECRET);
        OAuthState state = codec.newState("/admin/projects", "login");

        assertThat(codec.decode(codec.encode(state))).contains(state);
    }

    @Test
    void everySignInGetsItsOwnStateNonceAndVerifier() {
        OAuthStateCodec codec = codecAt(NOW, SECRET);
        OAuthState first = codec.newState("/admin/projects", "login");
        OAuthState second = codec.newState("/admin/projects", "login");

        assertThat(second.state()).isNotEqualTo(first.state());
        assertThat(second.nonce()).isNotEqualTo(first.nonce());
        assertThat(second.codeVerifier()).isNotEqualTo(first.codeVerifier());
        // RFC 7636: a verifier is 43 to 128 characters.
        assertThat(first.codeVerifier()).hasSizeBetween(43, 128);
    }

    @Test
    void refusesACookieWhosePayloadWasEdited() {
        OAuthStateCodec codec = codecAt(NOW, SECRET);
        String cookie = codec.encode(codec.newState("/admin/projects", "login"));
        String payload = cookie.substring(0, cookie.indexOf('.'));
        char flipped = payload.charAt(3) == 'A' ? 'B' : 'A';
        String edited = payload.substring(0, 3) + flipped + cookie.substring(4);

        assertThat(codec.decode(edited)).isEmpty();
    }

    @Test
    void refusesACookieSignedWithAnotherSecret() {
        OAuthStateCodec other = codecAt(NOW, "another_secret_that_is_also_at_least_32_chars");
        String cookie = other.encode(other.newState("/admin/projects", "login"));

        assertThat(codecAt(NOW, SECRET).decode(cookie)).isEmpty();
    }

    @Test
    void refusesAStateOlderThanTenMinutes() {
        String cookie = codecAt(NOW, SECRET).encode(codecAt(NOW, SECRET).newState("/admin/projects", "login"));

        assertThat(codecAt(NOW.plus(Duration.ofMinutes(9)), SECRET).decode(cookie)).isPresent();
        assertThat(codecAt(NOW.plus(Duration.ofMinutes(11)), SECRET).decode(cookie)).isEmpty();
    }

    @Test
    void refusesAnythingThatIsNotACookieItWrote() {
        OAuthStateCodec codec = codecAt(NOW, SECRET);

        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode("")).isEmpty();
        assertThat(codec.decode("no-dot")).isEmpty();
        assertThat(codec.decode("a.b.c")).isEmpty();
        assertThat(codec.decode("!!!.???")).isEmpty();
    }
}
