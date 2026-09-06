package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class PiiMaskingAndDebugLinksIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String jwtToken;
    private UUID projectId;

    @BeforeEach
    void setup() throws Exception {
        String email = "pii-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName("PII Test Org")
                .build();

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode authJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        jwtToken = authJson.get("accessToken").asText();

        ProjectRequest projectRequest = ProjectRequest.builder()
                .name("PII Test Project")
                .description("For PII masking integration tests")
                .build();

        MvcResult projectResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode projectJson = objectMapper.readTree(projectResult.getResponse().getContentAsString());
        projectId = UUID.fromString(projectJson.get("id").asText());
    }

    private String piiRulesUrl() {
        return "/api/v1/projects/" + projectId + "/pii-rules";
    }

    private String eventsUrl() {
        return "/api/v1/projects/" + projectId + "/events";
    }

    // ── PII Masking Rules CRUD ──

    @Test
    public void listRules_emptyByDefault() throws Exception {
        mockMvc.perform(get(piiRulesUrl())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    public void seedDefaults_createsBuiltinRules() throws Exception {
        mockMvc.perform(post(piiRulesUrl() + "/seed-defaults")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$[?(@.patternName == 'email')]").exists())
                .andExpect(jsonPath("$[?(@.patternName == 'phone')]").exists())
                .andExpect(jsonPath("$[?(@.patternName == 'card')]").exists())
                .andExpect(jsonPath("$[0].ruleType").value("BUILTIN"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    public void createRule_returnsCreated() throws Exception {
        String body = "{\"patternName\": \"ssn\", \"jsonPath\": \"$.user.ssn\", \"maskStyle\": \"FULL\", \"enabled\": true}";

        mockMvc.perform(post(piiRulesUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.patternName").value("ssn"))
                .andExpect(jsonPath("$.jsonPath").value("$.user.ssn"))
                .andExpect(jsonPath("$.maskStyle").value("FULL"))
                .andExpect(jsonPath("$.ruleType").value("CUSTOM"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    public void createRule_missingPatternName_returns400() throws Exception {
        String body = "{\"maskStyle\": \"FULL\"}";

        mockMvc.perform(post(piiRulesUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void updateRule_changesMaskStyle() throws Exception {
        String ruleId = createCustomRule("update_me", "PARTIAL");

        String updateBody = "{\"patternName\": \"update_me\", \"maskStyle\": \"HASH\", \"enabled\": true}";

        mockMvc.perform(put(piiRulesUrl() + "/" + ruleId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskStyle").value("HASH"));
    }

    @Test
    public void updateRule_toggleEnabled() throws Exception {
        String ruleId = createCustomRule("toggle_me", "FULL");

        String updateBody = "{\"patternName\": \"toggle_me\", \"maskStyle\": \"FULL\", \"enabled\": false}";

        mockMvc.perform(put(piiRulesUrl() + "/" + ruleId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    public void deleteRule_returns204() throws Exception {
        String ruleId = createCustomRule("delete_me", "FULL");

        mockMvc.perform(delete(piiRulesUrl() + "/" + ruleId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(piiRulesUrl())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.patternName == 'delete_me')]").doesNotExist());
    }

    @Test
    public void piiRules_noAuth_returns401() throws Exception {
        mockMvc.perform(get(piiRulesUrl()))
                .andExpect(status().isUnauthorized());
    }

    // ── PII Preview / Sanitization ──

    @Test
    public void previewSanitization_masksEmail() throws Exception {
        mockMvc.perform(post(piiRulesUrl() + "/seed-defaults")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        String payload = "{\"user\": {\"email\": \"john@example.com\", \"name\": \"John\"}}";

        MvcResult result = mockMvc.perform(post(piiRulesUrl() + "/preview")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();

        String sanitized = result.getResponse().getContentAsString();
        assert !sanitized.contains("john@example.com") : "Email should be masked in preview";
        assert sanitized.contains("John") : "Non-PII data should remain";
    }

    @Test
    public void previewSanitization_masksPhone() throws Exception {
        mockMvc.perform(post(piiRulesUrl() + "/seed-defaults")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        String payload = "{\"contact\": {\"phone\": \"+1-555-123-4567\"}}";

        MvcResult result = mockMvc.perform(post(piiRulesUrl() + "/preview")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();

        String sanitized = result.getResponse().getContentAsString();
        assert !sanitized.contains("+1-555-123-4567") : "Phone should be masked";
    }

    @Test
    public void previewSanitization_noRules_returnsUnchanged() throws Exception {
        String payload = "{\"data\": \"hello\"}";

        MvcResult result = mockMvc.perform(post(piiRulesUrl() + "/preview")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();

        String sanitized = result.getResponse().getContentAsString();
        assert sanitized.contains("hello") : "Data should remain unchanged with no rules";
    }

    // ── Sanitized Event Endpoint ──

    @Test
    public void getSanitizedEvent_masksPayload() throws Exception {
        mockMvc.perform(post(piiRulesUrl() + "/seed-defaults")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        String eventBody = "{\"type\": \"user.created\", \"data\": {\"email\": \"jane@secret.com\", \"name\": \"Jane\"}}";
        MvcResult eventResult = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody))
                .andExpect(status().isCreated())
                .andReturn();

        String eventId = objectMapper.readTree(eventResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult sanitizedResult = mockMvc.perform(get(eventsUrl() + "/" + eventId + "/sanitized")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.eventType").value("user.created"))
                .andReturn();

        String payload = objectMapper.readTree(sanitizedResult.getResponse().getContentAsString())
                .get("payload").asText();
        assert !payload.contains("jane@secret.com") : "Email should be masked in sanitized endpoint";
    }

    @Test
    public void getRegularEvent_keepsRawPayload() throws Exception {
        mockMvc.perform(post(piiRulesUrl() + "/seed-defaults")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        String eventBody = "{\"type\": \"user.raw\", \"data\": {\"email\": \"raw@test.com\", \"name\": \"Raw\"}}";
        MvcResult eventResult = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody))
                .andExpect(status().isCreated())
                .andReturn();

        String eventId = objectMapper.readTree(eventResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult rawResult = mockMvc.perform(get(eventsUrl() + "/" + eventId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andReturn();

        String payload = objectMapper.readTree(rawResult.getResponse().getContentAsString())
                .get("payload").asText();
        assert payload.contains("raw@test.com") : "Regular event endpoint should keep raw payload";
    }

    // ── Event Diff ──

    @Test
    public void diffEvents_findsChanges() throws Exception {
        String event1Body = "{\"type\": \"order.updated\", \"data\": {\"amount\": 100, \"status\": \"pending\"}}";
        String event2Body = "{\"type\": \"order.updated\", \"data\": {\"amount\": 200, \"status\": \"completed\"}}";

        MvcResult r1 = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event1Body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult r2 = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event2Body))
                .andExpect(status().isCreated())
                .andReturn();

        String leftId = objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asText();
        String rightId = objectMapper.readTree(r2.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get(eventsUrl() + "/diff?left=" + leftId + "&right=" + rightId + "&sanitize=false")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leftEventId").value(leftId))
                .andExpect(jsonPath("$.rightEventId").value(rightId))
                .andExpect(jsonPath("$.diffs").isArray())
                .andExpect(jsonPath("$.diffs.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$.diffs[?(@.path == '$.amount')]").exists())
                .andExpect(jsonPath("$.diffs[?(@.type == 'CHANGED')]").exists());
    }

    @Test
    public void diffEvents_identicalPayloads_emptyDiffs() throws Exception {
        String eventBody = "{\"type\": \"ping.test\", \"data\": {\"ok\": true}}";

        MvcResult r1 = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult r2 = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody))
                .andExpect(status().isCreated())
                .andReturn();

        String leftId = objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asText();
        String rightId = objectMapper.readTree(r2.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get(eventsUrl() + "/diff?left=" + leftId + "&right=" + rightId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diffs").isArray())
                .andExpect(jsonPath("$.diffs.length()").value(0));
    }

    @Test
    public void diffEvents_addedAndRemovedFields() throws Exception {
        String event1Body = "{\"type\": \"item.changed\", \"data\": {\"a\": 1, \"b\": 2}}";
        String event2Body = "{\"type\": \"item.changed\", \"data\": {\"b\": 2, \"c\": 3}}";

        MvcResult r1 = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event1Body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult r2 = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event2Body))
                .andExpect(status().isCreated())
                .andReturn();

        String leftId = objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asText();
        String rightId = objectMapper.readTree(r2.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get(eventsUrl() + "/diff?left=" + leftId + "&right=" + rightId + "&sanitize=false")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diffs[?(@.type == 'REMOVED')]").exists())
                .andExpect(jsonPath("$.diffs[?(@.type == 'ADDED')]").exists());
    }

    @Test
    public void diffEvents_withSanitize_masksPayloads() throws Exception {
        mockMvc.perform(post(piiRulesUrl() + "/seed-defaults")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        String event1Body = "{\"type\": \"user.diff\", \"data\": {\"email\": \"old@test.com\", \"age\": 25}}";
        String event2Body = "{\"type\": \"user.diff\", \"data\": {\"email\": \"new@test.com\", \"age\": 30}}";

        MvcResult r1 = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event1Body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult r2 = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event2Body))
                .andExpect(status().isCreated())
                .andReturn();

        String leftId = objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asText();
        String rightId = objectMapper.readTree(r2.getResponse().getContentAsString()).get("id").asText();

        MvcResult diffResult = mockMvc.perform(get(eventsUrl() + "/diff?left=" + leftId + "&right=" + rightId + "&sanitize=true")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leftPayload").exists())
                .andExpect(jsonPath("$.rightPayload").exists())
                .andReturn();

        String response = diffResult.getResponse().getContentAsString();
        assert !response.contains("old@test.com") : "Left email should be masked when sanitize=true";
        assert !response.contains("new@test.com") : "Right email should be masked when sanitize=true";
    }

    // ── Shared Debug Links ──

    @Test
    public void createDebugLink_returnsCreated() throws Exception {
        String eventId = createTestEvent();

        mockMvc.perform(post(eventsUrl() + "/" + eventId + "/debug-links")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryHours\": 24}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.shareUrl").isNotEmpty())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.viewCount").value(0));
    }

    @Test
    public void listDebugLinks_forEvent() throws Exception {
        String eventId = createTestEvent();

        mockMvc.perform(post(eventsUrl() + "/" + eventId + "/debug-links")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryHours\": 12}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(eventsUrl() + "/" + eventId + "/debug-links")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryHours\": 48}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(eventsUrl() + "/" + eventId + "/debug-links")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    public void deleteDebugLink_returns204() throws Exception {
        String eventId = createTestEvent();

        MvcResult createResult = mockMvc.perform(post(eventsUrl() + "/" + eventId + "/debug-links")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryHours\": 24}"))
                .andExpect(status().isCreated())
                .andReturn();

        String linkId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/debug-links/" + linkId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNoContent());
    }

    @Test
    public void viewPublicLink_returnsSanitizedPayload() throws Exception {
        mockMvc.perform(post(piiRulesUrl() + "/seed-defaults")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        String eventBody = "{\"type\": \"payment.processed\", \"data\": {\"email\": \"secret@user.com\", \"amount\": 42.50}}";
        MvcResult eventResult = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody))
                .andExpect(status().isCreated())
                .andReturn();
        String eventId = objectMapper.readTree(eventResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult linkResult = mockMvc.perform(post(eventsUrl() + "/" + eventId + "/debug-links")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryHours\": 24}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(linkResult.getResponse().getContentAsString()).get("token").asText();

        MvcResult publicResult = mockMvc.perform(get("/api/v1/public/debug/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("payment.processed"))
                .andExpect(jsonPath("$.sanitizedPayload").exists())
                .andExpect(jsonPath("$.projectName").value("PII Test Project"))
                .andExpect(jsonPath("$.eventCreatedAt").exists())
                .andExpect(jsonPath("$.linkExpiresAt").exists())
                .andReturn();

        String sanitizedPayload = objectMapper.readTree(publicResult.getResponse().getContentAsString())
                .get("sanitizedPayload").asText();
        assert !sanitizedPayload.contains("secret@user.com") : "Public link should mask PII";
        assert sanitizedPayload.contains("42.5") : "Non-PII data should remain in public link";
    }

    @Test
    public void viewPublicLink_invalidToken_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/public/debug/nonexistent-token-12345"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void debugLink_noAuth_returns401() throws Exception {
        String eventId = createTestEvent();

        mockMvc.perform(post(eventsUrl() + "/" + eventId + "/debug-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryHours\": 24}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Org Isolation ──

    @Test
    public void crossOrg_cannotAccessPiiRules() throws Exception {
        String otherToken = registerOtherOrg();

        mockMvc.perform(get(piiRulesUrl())
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    public void crossOrg_cannotCreateDebugLink() throws Exception {
        String eventId = createTestEvent();
        String otherToken = registerOtherOrg();

        mockMvc.perform(post(eventsUrl() + "/" + eventId + "/debug-links")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryHours\": 24}"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void crossOrg_cannotDiffEvents() throws Exception {
        String eventId = createTestEvent();
        String otherToken = registerOtherOrg();

        mockMvc.perform(get(eventsUrl() + "/diff?left=" + eventId + "&right=" + eventId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ──

    private String createTestEvent() throws Exception {
        String eventBody = "{\"type\": \"test.event\", \"data\": {\"key\": \"value\"}}";
        MvcResult result = mockMvc.perform(post(eventsUrl() + "/test")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createCustomRule(String patternName, String maskStyle) throws Exception {
        String body = "{\"patternName\": \"" + patternName + "\", \"maskStyle\": \"" + maskStyle + "\", \"enabled\": true}";
        MvcResult result = mockMvc.perform(post(piiRulesUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String registerOtherOrg() throws Exception {
        String otherEmail = "other-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        RegisterRequest otherReg = RegisterRequest.builder()
                .email(otherEmail)
                .password("Test1234!")
                .organizationName("Other Org")
                .build();

        MvcResult otherResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReg)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(otherResult.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
