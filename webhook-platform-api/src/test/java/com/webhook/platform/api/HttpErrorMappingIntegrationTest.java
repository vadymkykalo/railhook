package com.webhook.platform.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A malformed request is the caller's problem; a 5xx says it is ours. That
 * distinction is not cosmetic here — this platform's own retry ladder, and
 * every other sender's, treats 5xx as "try again" and 4xx as "stop". So a
 * request Spring rejects before it reaches a controller must not fall through
 * to the catch-all RuntimeException handler and come back as 500, or a
 * perfectly healthy API invites the caller to hammer it forever.
 *
 * <p>The same bug was already fixed once for NoResourceFoundException — see the
 * comment on {@code GlobalExceptionHandler.handleNoResourceFound}, which
 * describes a liveness probe reading 500 and concluding the API was broken.
 * These are the remaining members of that family.</p>
 */
@AutoConfigureMockMvc
public class HttpErrorMappingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void wrongMethodIs405AndSaysWhatIsAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.error").value("method_not_allowed"));
    }

    @Test
    public void malformedJsonIs400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));
    }

    @Test
    public void anEmptyBodyWhereOneIsRequiredIs400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));
    }

    @Test
    public void anUnsupportedContentTypeIs415() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_media_type"));
    }

    @Test
    public void wrongMethodOnTheIngressEndpointIs405NotAServerError() throws Exception {
        // Worth its own case: /ingress/** is the path third-party providers hit,
        // and it is the one place where a spurious 500 turns into someone else's
        // retry storm against us.
        mockMvc.perform(get("/ingress/whatever"))
                .andExpect(status().isMethodNotAllowed());
    }
}
