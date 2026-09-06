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

@AutoConfigureMockMvc
public class OrganizationIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testProjectIsolationBetweenOrganizations() throws Exception {
        RegisterRequest user1Request = RegisterRequest.builder()
                .email("user1@example.com")
                .password("Test1234!")
                .organizationName("Org 1")
                .build();

        MvcResult user1Result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user1Request)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse user1Auth = objectMapper.readValue(
                user1Result.getResponse().getContentAsString(),
                AuthResponse.class
        );

        RegisterRequest user2Request = RegisterRequest.builder()
                .email("user2@example.com")
                .password("Test1234!")
                .organizationName("Org 2")
                .build();

        MvcResult user2Result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user2Request)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse user2Auth = objectMapper.readValue(
                user2Result.getResponse().getContentAsString(),
                AuthResponse.class
        );

        ProjectRequest projectRequest = ProjectRequest.builder()
                .name("Project 1")
                .description("Test project")
                .build();

        MvcResult projectResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + user1Auth.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        ProjectResponse project = objectMapper.readValue(
                projectResult.getResponse().getContentAsString(),
                ProjectResponse.class
        );

        mockMvc.perform(get("/api/v1/projects/" + project.getId())
                        .header("Authorization", "Bearer " + user1Auth.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Project 1"));

        mockMvc.perform(get("/api/v1/projects/" + project.getId())
                        .header("Authorization", "Bearer " + user2Auth.getAccessToken()))
                // A resource in another organization is not found rather than forbidden: the tenant
        // filter means this caller's queries never see it, and answering 403 would
        // confirm the id exists.
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + user1Auth.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + user2Auth.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
