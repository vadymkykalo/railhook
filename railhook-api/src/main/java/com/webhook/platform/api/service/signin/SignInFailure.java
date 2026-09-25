package com.webhook.platform.api.service.signin;

// Only this code reaches the login page; the dashboard maps it to a sentence.
public enum SignInFailure {
    // The callback's state does not match this browser: forged, replayed or expired.
    STATE("google_state"),
    DENIED("google_denied"),
    UNAVAILABLE("google_unavailable"),
    IDENTITY("google_identity"),
    UNVERIFIED_EMAIL("google_unverified_email"),
    ACCOUNT_DISABLED("google_account_disabled");

    private final String code;

    SignInFailure(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
