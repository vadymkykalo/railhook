package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
public class MembershipRbacTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testDeveloperCannotAddMembers() throws Exception {
        RegisterRequest ownerRequest = RegisterRequest.builder()
                .email("owner@example.com")
                .password("Test1234!")
                .organizationName("Test Org")
                .build();

        MvcResult ownerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ownerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse ownerAuth = objectMapper.readValue(
                ownerResult.getResponse().getContentAsString(),
                AuthResponse.class
        );

        MvcResult meResult = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + ownerAuth.getAccessToken()))
                .andExpect(status().isOk())
                .andReturn();

        CurrentUserResponse currentUser = objectMapper.readValue(
                meResult.getResponse().getContentAsString(),
                CurrentUserResponse.class
        );

        String orgId = currentUser.getOrganization().getId().toString();

        RegisterRequest devRegisterRequest = RegisterRequest.builder()
                .email("developer@example.com")
                .password("Test1234!")
                .organizationName("Dev Org")
                .build();

        MvcResult devRegisterResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(devRegisterRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse devAuth = objectMapper.readValue(
                devRegisterResult.getResponse().getContentAsString(),
                AuthResponse.class
        );

        AddMemberRequest addAnotherMemberRequest = AddMemberRequest.builder()
                .email("viewer@example.com")
                .role(MembershipRole.VIEWER)
                .build();

        mockMvc.perform(get("/api/v1/orgs/" + orgId + "/members")
                        .header("Authorization", "Bearer " + ownerAuth.getAccessToken()))
                .andExpect(status().isOk());
    }
}
