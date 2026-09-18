package com.webhook.platform.api.mcp.oauth;

import org.springframework.http.HttpStatus;

/**
 * An OAuth error as the RFCs spell it: a registered {@code error} code the client acts on, and a
 * description a developer reads. Rendered as {@code {"error": ..., "error_description": ...}}
 * rather than through the API's own error shape, which no OAuth client understands.
 */
public class OAuthProtocolException extends RuntimeException {

    private final String error;
    private final HttpStatus status;

    public OAuthProtocolException(String error, String description) {
        this(error, description, HttpStatus.BAD_REQUEST);
    }

    public OAuthProtocolException(String error, String description, HttpStatus status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    public String error() {
        return error;
    }

    public HttpStatus status() {
        return status;
    }

    public static OAuthProtocolException invalidRequest(String description) {
        return new OAuthProtocolException("invalid_request", description);
    }

    public static OAuthProtocolException invalidGrant(String description) {
        return new OAuthProtocolException("invalid_grant", description);
    }

    public static OAuthProtocolException invalidClient(String description) {
        return new OAuthProtocolException("invalid_client", description, HttpStatus.UNAUTHORIZED);
    }
}
