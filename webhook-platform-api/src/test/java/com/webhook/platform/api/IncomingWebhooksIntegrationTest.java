package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.*;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IncomingWebhooksIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String accessToken;
    private static UUID projectId;
    private static UUID sourceId;
    private static UUID destinationId;
    private static String ingressPathToken;

    /**
     * Every Source now has an ingress rate limit — its own or the configured default — and the
     * limiter is fail-closed. {@code RedisRateLimiterService} is a mock here, so without this
     * every webhook posted below would be rejected with 429 before it reached the service.
     */
    @BeforeEach
    void allowIngressRateLimit() {
        when(redisRateLimiterService.tryAcquireForSourceFailClosed(any(UUID.class), anyInt()))
                .thenReturn(true);
    }

    private String auth() {
        return "Bearer " + accessToken;
    }

    @Test
    @Order(1)
    void setup_registerAndCreateProject() throws Exception {
        // Register user
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("incoming-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .password("Test1234!")
                .organizationName("Incoming Test Org")
                .build();

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();

        AuthResponse authResponse = objectMapper.readValue(
                registerResult.getResponse().getContentAsString(), AuthResponse.class);
        accessToken = authResponse.getAccessToken();

        // Get current user to find org
        MvcResult meResult = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andReturn();

        String meJson = meResult.getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(
                objectMapper.readTree(meJson).get("organization").get("id").asText());

        // Create project
        ProjectRequest projectRequest = ProjectRequest.builder()
                .name("Incoming Test Project")
                .build();

        MvcResult projectResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();

        projectId = UUID.fromString(
                objectMapper.readTree(projectResult.getResponse().getContentAsString()).get("id").asText());
    }

    // ==================== Incoming Source CRUD ====================

    @Test
    @Order(10)
    void createSource_success() throws Exception {
        Assumptions.assumeTrue(projectId != null, "Setup must succeed first");
        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("GitHub Webhooks")
                .slug("github-webhooks")
                .providerType(ProviderType.GITHUB)
                .verificationMode(VerificationMode.NONE)
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/incoming-sources")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("GitHub Webhooks"))
                .andExpect(jsonPath("$.slug").value("github-webhooks"))
                .andExpect(jsonPath("$.providerType").value("GITHUB"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.verificationMode").value("NONE"))
                .andExpect(jsonPath("$.ingressPathToken").isNotEmpty())
                .andExpect(jsonPath("$.ingressUrl").isNotEmpty())
                .andExpect(jsonPath("$.hmacSecretConfigured").value(false))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        sourceId = UUID.fromString(objectMapper.readTree(json).get("id").asText());
        ingressPathToken = objectMapper.readTree(json).get("ingressPathToken").asText();
    }

    @Test
    @Order(11)
    void createSource_duplicateSlug_fails() throws Exception {
        Assumptions.assumeTrue(sourceId != null, "Source must be created first");
        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("Another Source")
                .slug("github-webhooks")
                .build();

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/incoming-sources")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(12)
    void createSource_missingName_fails() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/incoming-sources")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"no-name\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(13)
    void getSource_success() throws Exception {
        Assumptions.assumeTrue(sourceId != null, "Source must be created first");
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-sources/" + sourceId)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sourceId.toString()))
                .andExpect(jsonPath("$.name").value("GitHub Webhooks"));
    }

    @Test
    @Order(14)
    void listSources_success() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-sources")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].name").value("GitHub Webhooks"));
    }

    @Test
    @Order(15)
    void updateSource_success() throws Exception {
        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("GitHub Webhooks Updated")
                .providerType(ProviderType.GITHUB)
                .status(IncomingSourceStatus.ACTIVE)
                .verificationMode(VerificationMode.HMAC_GENERIC)
                .hmacSecret("test-hmac-secret")
                .hmacHeaderName("X-Hub-Signature-256")
                .hmacSignaturePrefix("sha256=")
                .build();

        mockMvc.perform(put("/api/v1/projects/" + projectId + "/incoming-sources/" + sourceId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("GitHub Webhooks Updated"))
                .andExpect(jsonPath("$.verificationMode").value("HMAC_GENERIC"))
                .andExpect(jsonPath("$.hmacSecretConfigured").value(true))
                .andExpect(jsonPath("$.hmacHeaderName").value("X-Hub-Signature-256"))
                .andExpect(jsonPath("$.hmacSignaturePrefix").value("sha256="));
    }

    @Test
    @Order(16)
    void getSource_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-sources/" + UUID.randomUUID())
                        .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(17)
    void createSource_noAuth_unauthorized() throws Exception {
        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("No Auth").build();

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/incoming-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Incoming Destination CRUD ====================

    @Test
    @Order(20)
    void createDestination_success() throws Exception {
        Assumptions.assumeTrue(sourceId != null, "Source must be created first");
        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/webhook-receiver")
                .authType(IncomingAuthType.BEARER)
                .authConfig("{\"token\":\"secret-bearer-token\"}")
                .maxAttempts(3)
                .timeoutSeconds(15)
                .retryDelays("30,60,300")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId +
                        "/incoming-sources/" + sourceId + "/destinations")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.url").value("https://example.com/webhook-receiver"))
                .andExpect(jsonPath("$.authType").value("BEARER"))
                .andExpect(jsonPath("$.authConfigured").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.timeoutSeconds").value(15))
                .andExpect(jsonPath("$.retryDelays").value("30,60,300"))
                .andReturn();

        destinationId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    @Order(21)
    void getDestination_success() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId +
                        "/incoming-sources/" + sourceId + "/destinations/" + destinationId)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/webhook-receiver"));
    }

    @Test
    @Order(22)
    void listDestinations_success() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId +
                        "/incoming-sources/" + sourceId + "/destinations")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @Order(23)
    void updateDestination_success() throws Exception {
        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/updated-hook")
                .authType(IncomingAuthType.NONE)
                .enabled(false)
                .maxAttempts(10)
                .timeoutSeconds(60)
                .retryDelays("60,300,900,3600")
                .build();

        mockMvc.perform(put("/api/v1/projects/" + projectId +
                        "/incoming-sources/" + sourceId + "/destinations/" + destinationId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/updated-hook"))
                .andExpect(jsonPath("$.authType").value("NONE"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.maxAttempts").value(10));
    }

    // ==================== Ingress Endpoint ====================

    @Test
    @Order(30)
    void ingress_receiveWebhook_accepted() throws Exception {
        Assumptions.assumeTrue(ingressPathToken != null, "Source must be created first");
        // Re-enable destination for forwarding
        IncomingDestinationRequest enableReq = IncomingDestinationRequest.builder()
                .url("https://example.com/updated-hook")
                .enabled(true)
                .build();
        mockMvc.perform(put("/api/v1/projects/" + projectId +
                        "/incoming-sources/" + sourceId + "/destinations/" + destinationId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enableReq)))
                .andExpect(status().isOk());

        // Reset verification mode to NONE for basic ingress acceptance test
        IncomingSourceRequest resetVerification = IncomingSourceRequest.builder()
                .name("GitHub Webhooks Updated")
                .verificationMode(VerificationMode.NONE)
                .build();
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/incoming-sources/" + sourceId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetVerification)))
                .andExpect(status().isOk());

        // Send webhook to ingress endpoint (no auth required — public endpoint)
        mockMvc.perform(post("/ingress/" + ingressPathToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"push\",\"ref\":\"refs/heads/main\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @Order(31)
    void ingress_invalidToken_notFound() throws Exception {
        mockMvc.perform(post("/ingress/nonexistent-token-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"test\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    @Order(32)
    void ingress_emptyBody_accepted() throws Exception {
        Assumptions.assumeTrue(ingressPathToken != null, "Source must be created first");
        mockMvc.perform(post("/ingress/" + ingressPathToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    @Order(33)
    void ingress_signatureVerificationFailed_returns401() throws Exception {
        Assumptions.assumeTrue(ingressPathToken != null, "Source must be created first");

        // Enable HMAC verification on the source
        IncomingSourceRequest enableHmac = IncomingSourceRequest.builder()
                .name("GitHub Webhooks Updated")
                .verificationMode(VerificationMode.HMAC_GENERIC)
                .hmacSecret("test-hmac-secret")
                .hmacHeaderName("X-Signature")
                .hmacSignaturePrefix("")
                .build();
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/incoming-sources/" + sourceId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enableHmac)))
                .andExpect(status().isOk());

        // Send webhook with wrong signature — should be rejected
        mockMvc.perform(post("/ingress/" + ingressPathToken)
                        .header("X-Signature", "invalid-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"push\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("signature_verification_failed"));

        // Reset back to NONE for subsequent tests
        IncomingSourceRequest resetMode = IncomingSourceRequest.builder()
                .name("GitHub Webhooks Updated")
                .verificationMode(VerificationMode.NONE)
                .build();
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/incoming-sources/" + sourceId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetMode)))
                .andExpect(status().isOk());
    }

    @Test
    @Order(34)
    void ingress_emptyBody_withNoVerification_accepted() throws Exception {
        Assumptions.assumeTrue(ingressPathToken != null, "Source must be created first");
        mockMvc.perform(post("/ingress/" + ingressPathToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    // ==================== Incoming Events ====================

    @Test
    @Order(40)
    void listIncomingEvents_success() throws Exception {
        Assumptions.assumeTrue(projectId != null, "Setup must succeed first");
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-events")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].requestId").isNotEmpty())
                .andExpect(jsonPath("$.content[0].method").value("POST"));
    }

    @Test
    @Order(41)
    void listIncomingEvents_filterBySource() throws Exception {
        Assumptions.assumeTrue(sourceId != null, "Source must be created first");
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-events")
                        .param("sourceId", sourceId.toString())
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @Order(42)
    void getIncomingEvent_success() throws Exception {
        Assumptions.assumeTrue(projectId != null, "Setup must succeed first");
        // First get event ID from list
        MvcResult listResult = mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-events")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andReturn();

        String eventIdStr = objectMapper.readTree(listResult.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText();

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-events/" + eventIdStr)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventIdStr))
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.incomingSourceId").value(sourceId.toString()));
    }

    @Test
    @Order(43)
    void getEventAttempts_success() throws Exception {
        Assumptions.assumeTrue(projectId != null, "Setup must succeed first");
        MvcResult listResult = mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-events")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andReturn();

        String eventIdStr = objectMapper.readTree(listResult.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText();

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-events/" + eventIdStr + "/attempts")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ==================== Delete / Disable ====================

    @Test
    @Order(50)
    void deleteDestination_success() throws Exception {
        Assumptions.assumeTrue(destinationId != null, "Destination must be created first");
        mockMvc.perform(delete("/api/v1/projects/" + projectId +
                        "/incoming-sources/" + sourceId + "/destinations/" + destinationId)
                        .header("Authorization", auth()))
                .andExpect(status().isNoContent());

        // Verify it's gone
        mockMvc.perform(get("/api/v1/projects/" + projectId +
                        "/incoming-sources/" + sourceId + "/destinations/" + destinationId)
                        .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(51)
    void deleteSource_softDeletes() throws Exception {
        Assumptions.assumeTrue(sourceId != null, "Source must be created first");
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/incoming-sources/" + sourceId)
                        .header("Authorization", auth()))
                .andExpect(status().isNoContent());

        // Source still exists but is DISABLED
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/incoming-sources/" + sourceId)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    @Order(52)
    void ingress_disabledSource_returns410() throws Exception {
        Assumptions.assumeTrue(ingressPathToken != null, "Source must be created first");
        mockMvc.perform(post("/ingress/" + ingressPathToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"test\":true}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("disabled"));
    }
}
