package com.webhook.platform.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Secure in production or when the public address is https. Not unconditionally: browsers drop
 * Secure cookies over plain http except on localhost, and a plain-http install is supported.
 */
@Component
public class AuthCookies {

    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String SIGN_IN_STATE = "railhook_oauth_state";
    public static final String SIGN_IN_HANDOFF = "railhook_signin_handoff";

    private static final String REFRESH_TOKEN_PATH = "/api/v1/auth";
    private static final String SIGN_IN_STATE_PATH = "/api/v1/auth/oauth/google";
    private static final String SIGN_IN_HANDOFF_PATH = "/api/v1/auth/oauth/exchange";

    private final boolean production;
    private final boolean secure;
    private final Duration refreshTokenLifetime;

    public AuthCookies(@Value("${app.env:development}") String appEnv,
                       @Value("${app.base-url:http://localhost:5173}") String appBaseUrl,
                       @Value("${jwt.refresh-token-expiration:86400000}") long refreshTokenExpirationMs) {
        this.production = "production".equalsIgnoreCase(appEnv);
        this.secure = production || appBaseUrl.trim().toLowerCase(Locale.ROOT).startsWith("https://");
        this.refreshTokenLifetime = Duration.ofMillis(refreshTokenExpirationMs);
    }

    public ResponseCookie refreshToken(String token) {
        return refresh(token, refreshTokenLifetime);
    }

    public ResponseCookie clearedRefreshToken() {
        return refresh("", Duration.ZERO);
    }

    public ResponseCookie signInState(String value, Duration maxAge) {
        return signIn(SIGN_IN_STATE, SIGN_IN_STATE_PATH, value, maxAge);
    }

    public ResponseCookie clearedSignInState() {
        return signIn(SIGN_IN_STATE, SIGN_IN_STATE_PATH, "", Duration.ZERO);
    }

    public ResponseCookie signInHandoff(String browserBinding, Duration maxAge) {
        return signIn(SIGN_IN_HANDOFF, SIGN_IN_HANDOFF_PATH, browserBinding, maxAge);
    }

    public ResponseCookie clearedSignInHandoff() {
        return signIn(SIGN_IN_HANDOFF, SIGN_IN_HANDOFF_PATH, "", Duration.ZERO);
    }

    private ResponseCookie refresh(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_TOKEN, value)
                .httpOnly(true)
                .secure(secure)
                .path(REFRESH_TOKEN_PATH)
                .maxAge(maxAge)
                .sameSite(production ? "Strict" : "Lax")
                .build();
    }

    private ResponseCookie signIn(String name, String path, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .path(path)
                .maxAge(maxAge)
                .sameSite("Lax")
                .build();
    }
}
