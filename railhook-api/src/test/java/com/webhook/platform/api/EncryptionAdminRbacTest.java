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

/**
 * Encryption admin endpoints are cluster-operator operations
 * (EncryptionKeyRotationService touches every tenant's secrets, no org predicate),
 * so they must be unreachable by ordinary tenant users — including a user who is
 * OWNER of their own org, which is the exploit this test guards against.
 *
 * <p>Authorization is via the platform-admin operator credential
 * ({@code X-Platform-Admin-Token}, see {@code PlatformAdminAuthenticationFilter}),
 * completely independent of {@code MembershipRole}/org membership.
 */
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

        // This user is MembershipRole.OWNER of their own freshly-created org — exactly the
        // exploit path: OWNER-of-some-org must NOT satisfy a cluster-operator endpoint.
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
        // No Authentication ends up in the SecurityContext for an invalid admin token (same as
        // presenting nothing), so this hits the unauthenticated path (401), not access-denied
        // (403) — asserting 4xx either way keeps this from being coupled to that distinction.
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
        // Full stack: security filter chain -> controller -> EncryptionKeyRotationService ->
        // real ShedLock lock acquisition -> real Postgres repositories (Testcontainers), for
        // the authorized principal. Deep per-secret rotation behavior is covered by
        // EncryptionKeyRotationServiceTest; this confirms the authorized path actually reaches
        // and completes the operation via HTTP, which the RBAC fix must not break.
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
