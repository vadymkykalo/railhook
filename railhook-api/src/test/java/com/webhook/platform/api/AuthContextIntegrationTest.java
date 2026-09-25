package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class AuthContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String jwtToken;
    private String apiKey;
    private UUID projectId;
    private UUID organizationId;

    @BeforeEach
    void setup() throws Exception {
        String email = "authctx-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName("AuthCtx Test Org")
                .build();

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode authJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        jwtToken = authJson.get("accessToken").asText();

        MvcResult meResult = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode meJson = objectMapper.readTree(meResult.getResponse().getContentAsString());
        organizationId = UUID.fromString(meJson.get("organization").get("id").asText());

        ProjectRequest projectRequest = ProjectRequest.builder()
                .name("AuthCtx Test Project")
                .description("Test project")
                .build();

        MvcResult projectResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode projectJson = objectMapper.readTree(projectResult.getResponse().getContentAsString());
        projectId = UUID.fromString(projectJson.get("id").asText());

        ApiKeyRequest apiKeyRequest = ApiKeyRequest.builder()
                .name("test-sdk-key")
                .build();

        MvcResult apiKeyResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(apiKeyRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode apiKeyJson = objectMapper.readTree(apiKeyResult.getResponse().getContentAsString());
        apiKey = apiKeyJson.get("key").asText();
    }

    @Test
    public void jwt_listProjects() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void jwt_listEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/endpoints")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_listSubscriptions() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/subscriptions")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_listEvents() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/events")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_listApiKeys() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_listDlq() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/dlq")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_dashboard() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/projects/" + projectId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_listIncomingSources() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-sources")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_currentUser() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").exists())
                .andExpect(jsonPath("$.organization").exists());
    }

    @Test
    public void jwt_listOrganizations() throws Exception {
        mockMvc.perform(get("/api/v1/orgs")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void apiKey_listEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/endpoints")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk());
    }

    @Test
    public void apiKey_listSubscriptions() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/subscriptions")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk());
    }

    @Test
    public void apiKey_listEvents() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/events")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk());
    }

    @Test
    public void apiKey_listApiKeys() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/api-keys")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk());
    }

    @Test
    public void apiKey_listDlq() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/dlq")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk());
    }

    @Test
    public void apiKey_dashboard() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/projects/" + projectId)
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk());
    }

    @Test
    public void apiKey_listIncomingSources() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-sources")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk());
    }

    @Test
    public void apiKey_currentUser_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_listOrganizations_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/orgs")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    // A leaked READ_WRITE key could otherwise mint keys and outlive its own revocation.

    @Test
    public void apiKey_createApiKey_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("X-API-Key", readWriteApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder().name("minted-by-key").build())))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_rotateApiKey_forbidden() throws Exception {
        UUID target = createApiKeyWithJwt("rotate-target");
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys/" + target + "/rotate")
                        .header("X-API-Key", readWriteApiKey()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_revokeApiKey_forbidden() throws Exception {
        UUID target = createApiKeyWithJwt("revoke-target");
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/api-keys/" + target)
                        .header("X-API-Key", readWriteApiKey()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void jwt_rotateAndRevokeApiKey() throws Exception {
        UUID target = createApiKeyWithJwt("jwt-managed");
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys/" + target + "/rotate")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/api-keys/" + target)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNoContent());
    }

    private String readWriteApiKey() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder()
                                .name("read-write-key").scope(ApiKeyScope.READ_WRITE).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private UUID createApiKeyWithJwt(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder().name(name).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    public void apiKey_crossProject_forbidden() throws Exception {
        UUID otherProjectId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/projects/" + otherProjectId + "/endpoints")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    public void jwt_listMembers() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/" + organizationId + "/members")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void jwt_listMembers_wrongOrg_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/" + UUID.randomUUID() + "/members")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_listMembers_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/" + organizationId + "/members")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_billingOrganization_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/billing/organization")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_billingUsage_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/billing/usage")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_billingInvoices_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/billing/invoices")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_auditLog_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/audit-log")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_auditLogExport_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/audit-log/export")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    public void jwt_billingOrganization() throws Exception {
        mockMvc.perform(get("/api/v1/billing/organization")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_billingUsage() throws Exception {
        mockMvc.perform(get("/api/v1/billing/usage")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void jwt_auditLog() throws Exception {
        mockMvc.perform(get("/api/v1/audit-log")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void noAuth_projects_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void noAuth_endpoints_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/endpoints"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void noAuth_currentUser_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void noAuth_organizations_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/orgs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void noAuth_billingWebhook_stripe_notUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/billing/webhook/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"invoice.paid\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status != 401 : "Billing webhook must not return 401 (got " + status
                            + "). SecurityConfig permitAll() for /api/v1/billing/webhook/** may be broken.";
                });
    }

    @Test
    public void noAuth_billingWebhook_wayforpay_notUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/billing/webhook/wayforpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionStatus\":\"Approved\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status != 401 : "Billing webhook must not return 401 (got " + status
                            + "). SecurityConfig permitAll() for /api/v1/billing/webhook/** may be broken.";
                });
    }
}
