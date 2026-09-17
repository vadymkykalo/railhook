package com.webhook.platform.api.exception;

import com.webhook.platform.common.security.UrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.TypeInformation;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    /** Throws what a service or Spring Data throws, so the mapping is exercised over HTTP. */
    @RestController
    static class ThrowingController {
        @GetMapping("/unique-race")
        String uniqueRace() {
            throw new DataIntegrityViolationException(
                    "could not execute statement [ERROR: duplicate key value violates unique constraint "
                            + "\"uq_transformation_name_project\"]");
        }

        @GetMapping("/bogus-sort")
        String bogusSort() {
            throw new PropertyReferenceException("bogus", TypeInformation.of(ErrorResponse.class), List.of());
        }

        @GetMapping("/internal-state")
        String internalState() {
            throw new IllegalStateException("HMAC secret not configured for source 3f1c-internal");
        }

        @GetMapping("/validation")
        String validation() {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(handler)
            .build();

    @Test
    @DisplayName("a unique-constraint race is a 409 that does not name the constraint")
    void integrityViolationIsConflictWithoutInternals() throws Exception {
        mockMvc.perform(get("/unique-race"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(content().string(not(containsString("uq_transformation_name_project"))))
                .andExpect(content().string(not(containsString("duplicate key"))));
    }

    @Test
    @DisplayName("sorting by a property that does not exist is a 400, not a server error")
    void unknownSortPropertyIsBadRequest() throws Exception {
        mockMvc.perform(get("/bogus-sort"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_parameter"))
                .andExpect(jsonPath("$.message").value(containsString("bogus")));
    }

    @Test
    @DisplayName("an IllegalStateException is ours: 500, and its message stays in the log")
    void illegalStateIsServerErrorWithoutItsMessage() throws Exception {
        mockMvc.perform(get("/internal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("HMAC"))));
    }

    @Test
    @DisplayName("an IllegalArgumentException is still the caller's validation error, message kept")
    void illegalArgumentStaysBadRequestWithMessage() throws Exception {
        mockMvc.perform(get("/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("fromDate must be before toDate"));
    }
}
