package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.ApiKeyRequest;
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

public class SchemaRegistryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String jwtToken;
    private String apiKey;
    private UUID projectId;

    @BeforeEach
    void setup() throws Exception {
        String email = "schema-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName("Schema Test Org")
                .build();

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode authJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        jwtToken = authJson.get("accessToken").asText();

        ProjectRequest projectRequest = ProjectRequest.builder()
                .name("Schema Test Project")
                .description("For schema registry integration tests")
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
                .name("schema-test-key")
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

    private String schemasUrl() {
        return "/api/v1/projects/" + projectId + "/schemas";
    }

    // ── Event Type CRUD ──

    @Test
    public void createEventType_returnsCreated() throws Exception {
        mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"order.created\", \"description\": \"Order was placed\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("order.created"))
                .andExpect(jsonPath("$.description").value("Order was placed"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    public void createEventType_duplicate_returns400() throws Exception {
        mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"dup.event\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"dup.event\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void listEventTypes_returnsAll() throws Exception {
        mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"list.type.a\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"list.type.b\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(2)));
    }

    @Test
    public void getEventType_returnsDetails() throws Exception {
        MvcResult result = mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"get.detail\", \"description\": \"test\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get(schemasUrl() + "/" + id)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("get.detail"))
                .andExpect(jsonPath("$.description").value("test"));
    }

    @Test
    public void updateEventType_updatesDescription() throws Exception {
        MvcResult result = mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"update.me\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put(schemasUrl() + "/" + id)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"update.me\", \"description\": \"Updated!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated!"));
    }

    @Test
    public void deleteEventType_returns204() throws Exception {
        MvcResult result = mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"delete.me\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete(schemasUrl() + "/" + id)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(schemasUrl() + "/" + id)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    // ── Schema Versions ──

    @Test
    public void createSchemaVersion_returnsCreatedAsDraft() throws Exception {
        String eventTypeId = createEventType("version.test");

        String schema = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}";

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaJson\": " + objectMapper.writeValueAsString(schema) + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.fingerprint").exists())
                .andExpect(jsonPath("$.schemaJson").exists());
    }

    @Test
    public void createSchemaVersion_duplicate_returnsExisting() throws Exception {
        String eventTypeId = createEventType("dedup.test");

        String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"}}}";
        String body = "{\"schemaJson\": " + objectMapper.writeValueAsString(schema) + "}";

        MvcResult first = mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String id2 = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assert id1.equals(id2) : "Duplicate schema should return same version";
    }

    // ── Compatibility mode ──

    private static final String COMPAT_V1 =
            "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
                    + "\"note\":{\"type\":\"string\"}},\"required\":[\"id\"]}";

    private String postVersion(String eventTypeId, String schema, String mode) throws Exception {
        String body = mode == null
                ? "{\"schemaJson\": " + objectMapper.writeValueAsString(schema) + "}"
                : "{\"schemaJson\": " + objectMapper.writeValueAsString(schema)
                        + ", \"compatibilityMode\": \"" + mode + "\"}";
        return body;
    }

    @Test
    public void compatibility_backwardRefusesANewRequiredProperty() throws Exception {
        String eventTypeId = createEventType("compat.backward");
        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, COMPAT_V1, "BACKWARD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.compatibilityMode").value("BACKWARD"));

        String withNewRequired = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
                + "\"note\":{\"type\":\"string\"},\"label\":{\"type\":\"string\"}},"
                + "\"required\":[\"id\",\"label\"]}";

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, withNewRequired, null)))
                .andExpect(status().isBadRequest());

        // And it is refused rather than stored: still one version.
        mockMvc.perform(get(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    public void compatibility_theModeIsInheritedWhenTheNextVersionOmitsIt() throws Exception {
        String eventTypeId = createEventType("compat.inherited");
        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, COMPAT_V1, "FORWARD")))
                .andExpect(status().isCreated());

        // FORWARD refuses dropping a property the previous version required. The request below
        // names no mode at all, so the only thing that can refuse it is the inherited one.
        String dropsRequired = "{\"type\":\"object\",\"properties\":{\"note\":{\"type\":\"string\"}}}";

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, dropsRequired, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void compatibility_backwardAllowsAnAddedOptionalProperty() throws Exception {
        String eventTypeId = createEventType("compat.allowed");
        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, COMPAT_V1, "BACKWARD")))
                .andExpect(status().isCreated());

        String withOptional = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
                + "\"note\":{\"type\":\"string\"},\"label\":{\"type\":\"string\"}},"
                + "\"required\":[\"id\"]}";

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, withOptional, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.compatibilityMode").value("BACKWARD"));
    }

    @Test
    public void compatibility_noneChecksNothing() throws Exception {
        String eventTypeId = createEventType("compat.none");
        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, COMPAT_V1, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.compatibilityMode").value("NONE"));

        String unrecognisable = "{\"type\":\"object\",\"properties\":{\"total\":{\"type\":\"number\"}},"
                + "\"required\":[\"total\"]}";

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, unrecognisable, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    public void compatibility_anUnrecognisedModeIsRefusedRatherThanSilentlyMeaningNone() throws Exception {
        String eventTypeId = createEventType("compat.typo");

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postVersion(eventTypeId, COMPAT_V1, "BACKWARDS")))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void listVersions_returnsAll() throws Exception {
        String eventTypeId = createEventType("listver.test");

        String schema1 = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";
        String schema2 = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"number\"}}}";

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaJson\": " + objectMapper.writeValueAsString(schema1) + "}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaJson\": " + objectMapper.writeValueAsString(schema2) + "}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── Promote / Deprecate ──

    @Test
    public void promoteVersion_setsActive() throws Exception {
        String eventTypeId = createEventType("promote.test");
        String versionId = createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"p\":{\"type\":\"string\"}}}");

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions/" + versionId + "/promote")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get(schemasUrl() + "/" + eventTypeId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersionStatus").value("ACTIVE"));
    }

    @Test
    public void deprecateVersion_setsDeprecated() throws Exception {
        String eventTypeId = createEventType("deprecate.test");
        String versionId = createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"d\":{\"type\":\"integer\"}}}");

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions/" + versionId + "/promote")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions/" + versionId + "/deprecate")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPRECATED"));
    }

    @Test
    public void promoteNewVersion_deprecatesPrevious() throws Exception {
        String eventTypeId = createEventType("cascade.test");
        String v1 = createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"string\"}}}");

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions/" + v1 + "/promote")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String v2 = createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"string\"},\"w\":{\"type\":\"number\"}}}");

        mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions/" + v2 + "/promote")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get(schemasUrl() + "/" + eventTypeId + "/versions/" + v1)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPRECATED"));
    }

    // ── Schema Changes / Diff ──

    @Test
    public void schemaChanges_computedOnNewVersion() throws Exception {
        String eventTypeId = createEventType("diff.test");

        createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"amount\":{\"type\":\"number\"}}}");

        mockMvc.perform(get(schemasUrl() + "/" + eventTypeId + "/changes")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].changeSummary").exists())
                .andExpect(jsonPath("$[0].fromVersion").value(1))
                .andExpect(jsonPath("$[0].toVersion").value(2));
    }

    @Test
    public void schemaChanges_breakingDetected() throws Exception {
        String eventTypeId = createEventType("breaking.test");

        createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}");
        createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"}},\"required\":[\"id\",\"code\"]}");

        mockMvc.perform(get(schemasUrl() + "/" + eventTypeId + "/changes")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].breaking").value(true));
    }

    @Test
    public void projectChanges_returnsAllAcrossEventTypes() throws Exception {
        String et1 = createEventType("global.a");
        String et2 = createEventType("global.b");

        createVersion(et1, "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        createVersion(et1, "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"number\"}}}");
        createVersion(et2, "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}}}");
        createVersion(et2, "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"boolean\"}}}");

        mockMvc.perform(get(schemasUrl() + "/changes")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventTypeName").exists());
    }

    // ── Event type catalog reflects versions ──

    @Test
    public void eventTypeCatalog_showsLatestVersion() throws Exception {
        String eventTypeId = createEventType("catalog.ver");

        createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"v1\":{\"type\":\"string\"}}}");
        createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"v1\":{\"type\":\"string\"},\"v2\":{\"type\":\"number\"}}}");

        mockMvc.perform(get(schemasUrl() + "/" + eventTypeId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value(2));
    }

    @Test
    public void eventTypeCatalog_showsBreakingFlag() throws Exception {
        String eventTypeId = createEventType("catalog.breaking");

        createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}");
        createVersion(eventTypeId, "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"string\"}},\"required\":[\"a\",\"b\"]}");

        mockMvc.perform(get(schemasUrl() + "/" + eventTypeId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasBreakingChanges").value(true));
    }

    // ── API Key auth works for schemas ──

    @Test
    public void apiKey_listEventTypes() throws Exception {
        mockMvc.perform(get(schemasUrl())
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void apiKey_createEventType() throws Exception {
        mockMvc.perform(post(schemasUrl())
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"apikey.event\"}"))
                .andExpect(status().isCreated());
    }

    // ── Auth: no token → 401 ──

    @Test
    public void noAuth_listSchemas_unauthorized() throws Exception {
        mockMvc.perform(get(schemasUrl()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void noAuth_createEventType_unauthorized() throws Exception {
        mockMvc.perform(post(schemasUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"no.auth\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Org isolation: other org cannot access ──

    @Test
    public void crossOrg_forbidden() throws Exception {
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

        String otherToken = objectMapper.readTree(otherResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get(schemasUrl())
                        .header("Authorization", "Bearer " + otherToken))
                // A resource in another organization is not found rather than forbidden: the tenant
        // filter means this caller's queries never see it, and answering 403 would
        // confirm the id exists.
                .andExpect(status().isNotFound());
    }

    // ── Project schema validation settings ──

    @Test
    public void projectSchemaValidation_defaultsOff() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaValidationEnabled").value(false))
                .andExpect(jsonPath("$.schemaValidationPolicy").value("WARN"));
    }

    @Test
    public void projectSchemaValidation_canBeEnabled() throws Exception {
        mockMvc.perform(put("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Schema Test Project\", \"schemaValidationEnabled\": true, \"schemaValidationPolicy\": \"BLOCK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaValidationEnabled").value(true))
                .andExpect(jsonPath("$.schemaValidationPolicy").value("BLOCK"));

        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaValidationEnabled").value(true))
                .andExpect(jsonPath("$.schemaValidationPolicy").value("BLOCK"));
    }

    // ── Helpers ──

    private String createEventType(String name) throws Exception {
        MvcResult result = mockMvc.perform(post(schemasUrl())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createVersion(String eventTypeId, String schema) throws Exception {
        MvcResult result = mockMvc.perform(post(schemasUrl() + "/" + eventTypeId + "/versions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaJson\": " + objectMapper.writeValueAsString(schema) + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
