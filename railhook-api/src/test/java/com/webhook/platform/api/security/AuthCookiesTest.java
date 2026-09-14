package com.webhook.platform.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCookiesTest {

    private static final long ONE_DAY_MS = Duration.ofDays(1).toMillis();

    @Test
    void productionRefreshCookieIsSecureHttpOnlyAndStrict() {
        AuthCookies cookies = new AuthCookies("production", "https://railhook.io", ONE_DAY_MS);

        ResponseCookie cookie = cookies.refreshToken("token");

        assertThat(cookie.getName()).isEqualTo(AuthCookies.REFRESH_TOKEN);
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void aDeploymentServedOverHttpsMarksEveryCookieSecureEvenOutsideProduction() {
        AuthCookies cookies = new AuthCookies("development", "https://hooks.example.com", ONE_DAY_MS);

        assertThat(cookies.refreshToken("token").isSecure()).isTrue();
        assertThat(cookies.clearedRefreshToken().isSecure()).isTrue();
        assertThat(cookies.signInState("state", Duration.ofMinutes(10)).isSecure()).isTrue();
        assertThat(cookies.signInHandoff("binding", Duration.ofSeconds(60)).isSecure()).isTrue();
    }

    @Test
    void aPlainHttpInstallKeepsCookiesItsBrowserWillAccept() {
        // A browser drops a Secure cookie set over http from anywhere but localhost, so an install
        // on http://<address> would never stay signed in.
        AuthCookies cookies = new AuthCookies("development", "http://192.168.1.10", ONE_DAY_MS);

        ResponseCookie cookie = cookies.refreshToken("token");

        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
    }

    @Test
    void clearingTheRefreshCookieExpiresItOnTheSamePath() {
        AuthCookies cookies = new AuthCookies("production", "https://railhook.io", ONE_DAY_MS);

        ResponseCookie cookie = cookies.clearedRefreshToken();

        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
    }

    @Test
    void signInCookiesAreLaxBecauseGoogleSendsTheBrowserBackCrossSite() {
        AuthCookies cookies = new AuthCookies("production", "https://railhook.io", ONE_DAY_MS);

        ResponseCookie state = cookies.signInState("state", Duration.ofMinutes(10));
        assertThat(state.getSameSite()).isEqualTo("Lax");
        assertThat(state.isHttpOnly()).isTrue();
        assertThat(state.getPath()).isEqualTo("/api/v1/auth/oauth/google");
        assertThat(state.getMaxAge()).isEqualTo(Duration.ofMinutes(10));

        ResponseCookie handoff = cookies.signInHandoff("binding", Duration.ofSeconds(60));
        assertThat(handoff.getSameSite()).isEqualTo("Lax");
        assertThat(handoff.isHttpOnly()).isTrue();
        assertThat(handoff.getPath()).isEqualTo("/api/v1/auth/oauth/exchange");
        assertThat(cookies.clearedSignInHandoff().getMaxAge()).isZero();
        assertThat(cookies.clearedSignInState().getMaxAge()).isZero();
    }
}
