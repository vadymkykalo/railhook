package com.webhook.platform.api.exception;

public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The {@code error} field of the response body. */
    public String getCode() {
        return "forbidden";
    }
}
