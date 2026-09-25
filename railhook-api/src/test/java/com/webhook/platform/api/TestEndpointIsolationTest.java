package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
public class TestEndpointIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String orgAJwt;
    private String orgBJwt;
    private UUID projectAId;
    private UUID testEndpointAId;
    private String testEndpointASlug;

    private UUID projectA2Id;
    private String apiKeyForProjectA;

    @BeforeEach
    void setup() throws Exception {
        // The capture endpoint's Redis limiter is mocked; stub it open or the capture answers 429.
        when(redisRateLimiterService.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        orgAJwt = registerAndGetJwt("orgA-" + suffix + "@test.com", "Org A " + suffix);
        orgBJwt = registerAndGetJwt("orgB-" + suffix + "@test.com", "Org B " + suffix);

        projectAId = createProject(orgAJwt, "Project A");
        projectA2Id = createProject(orgAJwt, "Project A2");

        MvcResult createResult = mockMvc.perform(post("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgAJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        testEndpointAId = UUID.fromString(created.get("id").asText());
        testEndpointASlug = created.get("slug").asText();

        mockMvc.perform(post("/hook/" + testEndpointASlug)
                        .header("Authorization", "Bearer super-secret-provider-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hello\":\"world\"}"))
                .andExpect(status().isOk());

        MvcResult apiKeyResult = mockMvc.perform(post("/api/v1/projects/" + projectAId + "/api-keys")
                        .header("Authorization", "Bearer " + orgAJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder()
                                .name("test-endpoint-key")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode apiKeyJson = objectMapper.readTree(apiKeyResult.getResponse().getContentAsString());
        apiKeyForProjectA = apiKeyJson.get("key").asText();
    }

    private String registerAndGetJwt(String email, String orgName) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName(orgName)
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private UUID createProject(String jwt, String name) throws Exception {
        ProjectRequest request = ProjectRequest.builder()
                .name(name)
                .description("Test project")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("id").asText());
    }

    // The body was once read twice and stored empty.
    @Test
    public void capturedRequest_keepsTheBodyAsSent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId + "/requests")
                        .header("Authorization", "Bearer " + orgAJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode captured = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode first = captured.isArray() ? captured.get(0) : captured.path("content").get(0);
        org.junit.jupiter.api.Assertions.assertEquals("{\"hello\":\"world\"}", first.get("body").asText());
    }

    @Test
    public void orgB_create_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgBJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                // Not found rather than forbidden: 403 would confirm the id exists.
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_list_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgBJwt))
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_get_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId)
                        .header("Authorization", "Bearer " + orgBJwt))
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_delete_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId)
                        .header("Authorization", "Bearer " + orgBJwt))
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_getRequests_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId + "/requests")
                        .header("Authorization", "Bearer " + orgBJwt))
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_clearRequests_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId + "/requests")
                        .header("Authorization", "Bearer " + orgBJwt))
                .andExpect(status().isNotFound());
    }

    @Test
    public void apiKey_crossProjectSameOrg_list_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectA2Id + "/test-endpoints")
                        .header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_crossProjectSameOrg_create_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectA2Id + "/test-endpoints")
                        .header("X-API-Key", apiKeyForProjectA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void orgA_list_ok() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgAJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    public void orgA_get_ok() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId)
                        .header("Authorization", "Bearer " + orgAJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testEndpointAId.toString()));
    }

    @Test
    public void orgA_getRequests_ok() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId + "/requests")
                        .header("Authorization", "Bearer " + orgAJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    public void orgA_clearRequests_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId + "/requests")
                        .header("Authorization", "Bearer " + orgAJwt))
                .andExpect(status().isNoContent());
    }

    @Test
    public void orgA_create_ok() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgAJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    public void orgA_delete_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId)
                        .header("Authorization", "Bearer " + orgAJwt))
                .andExpect(status().isNoContent());
    }
}
