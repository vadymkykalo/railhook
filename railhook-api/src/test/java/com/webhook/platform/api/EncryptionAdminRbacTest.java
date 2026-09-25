package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class EncryptionAdminRbacTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String registerAndGetAccessToken(String email) throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName("Org for " + email)
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class);
        return auth.getAccessToken();
    }

    @Test
    @DisplayName("a plain registered user (OWNER of their own org) gets 403 on /rotate")
    void plainUserForbiddenOnRotate() throws Exception {
        String accessToken = registerAndGetAccessToken("plain-owner-rotate@example.com");

        mockMvc.perform(post("/api/v1/admin/encryption/rotate")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a plain registered user (OWNER of their own org) gets 403 on /status")
    void plainUserForbiddenOnStatus() throws Exception {
        String accessToken = registerAndGetAccessToken("plain-owner-status@example.com");

        mockMvc.perform(get("/api/v1/admin/encryption/status")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unauthenticated caller gets 401/403 on /rotate and /status (no JWT, no admin token)")
    void unauthenticatedForbiddenOnBothEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/admin/encryption/rotate"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/v1/admin/encryption/status"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a wrong admin token is rejected exactly like no token at all")
    void wrongAdminTokenForbidden() throws Exception {
        // An invalid admin token may read as 401 or 403; asserting 4xx avoids coupling to that.
        mockMvc.perform(post("/api/v1/admin/encryption/rotate")
                        .header("X-Platform-Admin-Token", "not-the-real-token"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the platform admin operator credential gets 200 on /status and it does not require a JWT")
    void platformAdminAllowedOnStatus() throws Exception {
        mockMvc.perform(get("/api/v1/admin/encryption/status")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the platform admin operator credential gets 200 on /rotate — rotation runs end-to-end")
    void platformAdminAllowedOnRotateEndToEnd() throws Exception {
        mockMvc.perform(post("/api/v1/admin/encryption/rotate")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an OWNER's JWT plus a wrong admin token header is still forbidden (no privilege stacking)")
    void ownerJwtWithWrongAdminTokenStillForbidden() throws Exception {
        String accessToken = registerAndGetAccessToken("owner-plus-wrong-token@example.com");

        mockMvc.perform(post("/api/v1/admin/encryption/rotate")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Platform-Admin-Token", "still-not-the-real-token"))
                .andExpect(status().isForbidden());
    }
}
