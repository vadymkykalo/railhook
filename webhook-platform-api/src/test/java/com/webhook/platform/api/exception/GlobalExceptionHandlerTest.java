package com.webhook.platform.api.exception;

import com.webhook.platform.common.security.UrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rejected webhook URL is the caller's mistake, not ours.
 *
 * `UrlValidator.InvalidUrlException` is a RuntimeException, so before this it
 * fell through to the catch-all RuntimeException handler and came back as
 * 500 "An unexpected error occurred". Endpoint create and update call
 * `validateWebhookUrl` without catching it (EndpointService lines 92 and 154 —
 * only `testEndpoint` catches), so a user who typed a host that does not
 * resolve was told the server had broken, with nothing naming the URL.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a URL the validator rejected is a 400 that names the reason")
    void invalidUrlIsBadRequest() {
        UrlValidator.InvalidUrlException ex =
                new UrlValidator.InvalidUrlException("Cannot resolve host: api.acme.com: Name or service not known");

        ResponseEntity<ErrorResponse> response = handler.handleInvalidUrlException(ex, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getError()).isEqualTo("invalid_url");
        // The reason has to survive: "an unexpected error occurred" is what this replaces.
        assertThat(response.getBody().getMessage()).contains("api.acme.com");
    }

    @Test
    @DisplayName("an unmapped path is a 404, not a server error")
    void unmappedPathIsNotFound() {
        ResponseEntity<ErrorResponse> response =
                handler.handleNoResourceFound(
                        // Framework 7 takes the request URI as well as the resource path.
                        new NoResourceFoundException(HttpMethod.GET, "/actuator/health", "actuator/health"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getError()).isEqualTo("not_found");
    }

    @Test
    @DisplayName("an unmapped RuntimeException still reports as a server error")
    void unmappedRuntimeExceptionStaysServerError() {
        ResponseEntity<ErrorResponse> response =
                handler.handleRuntimeException(new IllegalStateException("boom"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
    }
}
