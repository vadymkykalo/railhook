package com.webhook.platform.api.service.signin;

/**
 * Why a Google sign-in did not complete, as the code the login page is sent back with.
 * The dashboard turns each code into a sentence; nothing more specific leaves the server.
 */
public enum SignInFailure {
    /** The callback did not carry the state this browser started with: forged, replayed, or too old. */
    STATE("google_state"),
    /** The person declined on Google's consent screen. */
    DENIED("google_denied"),
    /** Google could not be reached, or would not exchange the code. */
    UNAVAILABLE("google_unavailable"),
    /** The ID token failed a check: signature, issuer, audience, nonce or expiry. */
    IDENTITY("google_identity"),
    /** Google has not verified the address, so it proves nothing about who owns it. */
    UNVERIFIED_EMAIL("google_unverified_email"),
    /** The account exists and has been disabled. */
    ACCOUNT_DISABLED("google_account_disabled");

    private final String code;

    SignInFailure(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
