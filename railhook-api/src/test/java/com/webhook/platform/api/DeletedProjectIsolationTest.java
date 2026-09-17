package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.ApiKey;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.repository.ApiKeyRepository;
import com.webhook.platform.api.dto.ApiKeyRequest;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A deleted project is gone, on every path that can reach it.
 *
 * <p>Deleting a project only stamps {@code deleted_at}, and for a long time nothing but the
 * project list read that column: the project's API keys went on authenticating, its incoming
 * sources went on receiving webhooks, and its test endpoints went on capturing — while the plan's
 * project quota, which does count only live projects, let the owner create a replacement. Each
 * test here takes one of those paths and asks for the answer a project that never existed gets.
 */
@AutoConfigureMockMvc
public class DeletedProjectIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    private String jwt;
    private UUID projectId;

    @BeforeEach
    void setUp() throws Exception {
        when(redisRateLimiterService.tryAcquireForSourceFailClosed(any(UUID.class), anyInt())).thenReturn(true);
        when(redisRateLimiterService.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email("deleted-project-" + suffix + "@example.com")
                                .password("Test1234!")
                                .organizationName("Deleted Project Co " + suffix)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        jwt = read(registered).get("accessToken").asText();

        MvcResult project = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder()
                                .name("Doomed " + suffix)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        projectId = UUID.fromString(read(project).get("id").asText());
    }

    @Test
    void aDeletedProjectIsNotFound() throws Exception {
        deleteProject();

        mockMvc.perform(get("/api/v1/projects/" + projectId).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDeletedProjectCannotBeEdited() throws Exception {
        deleteProject();

        mockMvc.perform(put("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Resurrected\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDeletedProjectCannotBeDeletedAgain() throws Exception {
        deleteProject();

        mockMvc.perform(delete("/api/v1/projects/" + projectId).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDeletedProjectsResourcesAreNotFound() throws Exception {
        deleteProject();

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/test-endpoints").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anApiKeyOfADeletedProjectNoLongerAuthenticates() throws Exception {
        String key = createApiKey();
        deleteProject();

        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"order.created\",\"data\":{\"id\":1}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletingAProjectRevokesItsApiKeys() throws Exception {
        String key = createApiKey();
        deleteProject();

        ApiKey stored = TenantContext.callAsSystem(
                () -> apiKeyRepository.findByKeyHash(CryptoUtils.hashApiKey(key))).orElseThrow();
        assertThat(stored.getRevokedAt()).isNotNull();
    }

    @Test
    void anIncomingSourceOfADeletedProjectIsNotFound() throws Exception {
        MvcResult source = mockMvc.perform(post("/api/v1/projects/" + projectId + "/incoming-sources")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Doomed source\",\"slug\":\"doomed-source\","
                                + "\"providerType\":\"GENERIC\",\"verificationMode\":\"NONE\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = read(source).get("ingressPathToken").asText();

        mockMvc.perform(post("/ingress/" + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"before\":\"delete\"}"))
                .andExpect(status().isAccepted());

        deleteProject();

        mockMvc.perform(post("/ingress/" + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"after\":\"delete\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTestEndpointOfADeletedProjectIsNotFound() throws Exception {
        MvcResult endpoint = mockMvc.perform(post("/api/v1/projects/" + projectId + "/test-endpoints")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Doomed capture\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String slug = read(endpoint).get("slug").asText();

        mockMvc.perform(post("/hook/" + slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"before\":\"delete\"}"))
                .andExpect(status().isOk());

        deleteProject();

        mockMvc.perform(post("/hook/" + slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"after\":\"delete\"}"))
                .andExpect(status().isNotFound());
    }

    private String createApiKey() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder()
                                .name("doomed-key")
                                .scope(ApiKeyScope.READ_WRITE)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("key").asText();
    }

    private void deleteProject() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + projectId).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
    }

    private String bearer() {
        return "Bearer " + jwt;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
