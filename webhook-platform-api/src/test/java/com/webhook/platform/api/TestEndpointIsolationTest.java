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

/**
 * Regression coverage: TestEndpointController had no tenancy check at
 * all, so any authenticated user from any organization could list, read, or
 * delete another organization's test endpoints and their captured webhook
 * traffic (headers, bodies, including Authorization/signature headers).
 *
 * Convention (matches {@link OrganizationIsolationTest} /
 * {@link AuthContextIntegrationTest}): cross-org access is denied with 403.
 */
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

    // Second project under org A, used for the API-key cross-project check.
    private UUID projectA2Id;
    private String apiKeyForProjectA;

    @BeforeEach
    void setup() throws Exception {
        // WebhookCaptureController is backed by the Redis rate limiter, which
        // AbstractIntegrationTest mocks out; stub it open so the capture below
        // (used to seed real captured-request data) isn't rejected as 429.
        when(redisRateLimiterService.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        orgAJwt = registerAndGetJwt("orgA-" + suffix + "@test.com", "Org A " + suffix);
        orgBJwt = registerAndGetJwt("orgB-" + suffix + "@test.com", "Org B " + suffix);

        projectAId = createProject(orgAJwt, "Project A");
        projectA2Id = createProject(orgAJwt, "Project A2");

        // Create a test endpoint owned by org A / project A.
        MvcResult createResult = mockMvc.perform(post("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgAJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        testEndpointAId = UUID.fromString(created.get("id").asText());
        testEndpointASlug = created.get("slug").asText();

        // Capture a real inbound request (with a sensitive header) so the isolation
        // check on GET .../requests is exercised against real captured data.
        mockMvc.perform(post("/hook/" + testEndpointASlug)
                        .header("Authorization", "Bearer super-secret-provider-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hello\":\"world\"}"))
                .andExpect(status().isOk());

        // API key scoped to project A only (not project A2), for the cross-project check.
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

    // ── org B's JWT must be denied on every one of the six handlers ──

    @Test
    public void orgB_create_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgBJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                // A resource in another organization is not found rather than forbidden: the tenant
        // filter means this caller's queries never see it, and answering 403 would
        // confirm the id exists.
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_list_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgBJwt))
                // A resource in another organization is not found rather than forbidden: the tenant
        // filter means this caller's queries never see it, and answering 403 would
        // confirm the id exists.
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_get_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId)
                        .header("Authorization", "Bearer " + orgBJwt))
                // A resource in another organization is not found rather than forbidden: the tenant
        // filter means this caller's queries never see it, and answering 403 would
        // confirm the id exists.
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_delete_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId)
                        .header("Authorization", "Bearer " + orgBJwt))
                // A resource in another organization is not found rather than forbidden: the tenant
        // filter means this caller's queries never see it, and answering 403 would
        // confirm the id exists.
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_getRequests_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId + "/requests")
                        .header("Authorization", "Bearer " + orgBJwt))
                // A resource in another organization is not found rather than forbidden: the tenant
        // filter means this caller's queries never see it, and answering 403 would
        // confirm the id exists.
                .andExpect(status().isNotFound());
    }

    @Test
    public void orgB_clearRequests_onOrgAProject_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId + "/requests")
                        .header("Authorization", "Bearer " + orgBJwt))
                // A resource in another organization is not found rather than forbidden: the tenant
        // filter means this caller's queries never see it, and answering 403 would
        // confirm the id exists.
                .andExpect(status().isNotFound());
    }

    // ── API key scoped to project A cannot reach project A2 in the same org ──

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

    // ── the owning org still gets 200 on all six handlers (no lockout) ──

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
