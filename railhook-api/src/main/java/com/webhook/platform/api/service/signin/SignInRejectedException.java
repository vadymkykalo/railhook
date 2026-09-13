package com.webhook.platform.api.service.signin;

/** A Google sign-in that must not complete. The message is for the log; the failure is for the page. */
public class SignInRejectedException extends RuntimeException {

    private final SignInFailure failure;

    public SignInRejectedException(SignInFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public SignInFailure failure() {
        return failure;
    }
}
