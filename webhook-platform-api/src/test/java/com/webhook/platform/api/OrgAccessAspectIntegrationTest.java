package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that the {@code @RequireOrgAccess} AOP annotation correctly blocks
 * cross-organization access on the Members API.
 */
@AutoConfigureMockMvc
public class OrgAccessAspectIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testMembersEndpointRejectsCrossOrgAccess() throws Exception {
        // ---- Register user1 (Org A) ----
        RegisterRequest user1Request = RegisterRequest.builder()
                .email("orgaccess_user1@example.com")
                .password("Test1234!")
                .organizationName("Org A - Access Test")
                .build();

        MvcResult user1Result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(user1Request)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse user1Auth = objectMapper.readValue(
                user1Result.getResponse().getContentAsString(),
                AuthResponse.class);

        // ---- Get user1's organization ID ----
        MvcResult me1Result = mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + user1Auth.getAccessToken()))
                .andExpect(status().isOk())
                .andReturn();

        CurrentUserResponse currentUser1 = objectMapper.readValue(
                me1Result.getResponse().getContentAsString(),
                CurrentUserResponse.class);

        String orgAId = currentUser1.getOrganization().getId().toString();

        // ---- Register user2 (Org B) ----
        RegisterRequest user2Request = RegisterRequest.builder()
                .email("orgaccess_user2@example.com")
                .password("Test1234!")
                .organizationName("Org B - Access Test")
                .build();

        MvcResult user2Result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(user2Request)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse user2Auth = objectMapper.readValue(
                user2Result.getResponse().getContentAsString(),
                AuthResponse.class);

        // ---- user1 can list members of their own org → 200 OK ----
        mockMvc.perform(get("/api/v1/orgs/" + orgAId + "/members")
                .header("Authorization", "Bearer " + user1Auth.getAccessToken()))
                .andExpect(status().isOk());

        // ---- user2 cannot list members of Org A → 403 Forbidden ----
        mockMvc.perform(get("/api/v1/orgs/" + orgAId + "/members")
                .header("Authorization", "Bearer " + user2Auth.getAccessToken()))
                .andExpect(status().isForbidden());
    }
}
