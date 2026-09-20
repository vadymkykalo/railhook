package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The version number a Transformation shows is a promise that the template it used to hold can be
 * read back and put back. Before this, nothing stored a previous template: the counter went up and
 * the old text was overwritten, so "v3" in the list was a number with nothing behind it.
 *
 * <p>What is asserted here is the promise, through the HTTP surface: every published template is
 * listed with who published it and when, any one of them can be fetched or compared with another,
 * and restoring one moves the transformation forward to a new version rather than rewriting the
 * history that led to it.
 */
@DisplayName("Transformation version history — every published template is kept, readable and restorable")
@TestPropertySource(properties = "transformations.version-history-limit=5")
class TransformationVersionHistoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String jwt;
    private UUID projectId;
    private String email;

    private static final String V1 = "{\"a\":\"${$.one}\"}";
    private static final String V2 = "{\"a\":\"${$.two}\",\"b\":1}";
    private static final String V3 = "{\"a\":\"${$.three}\",\"b\":2}";

    @BeforeEach
    void signUpAndCreateProject() throws Exception {
        email = "tfhist-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email(email)
                                .password("Test1234!")
                                .organizationName("Transformation History Org")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        jwt = json(registered).get("accessToken").asText();

        MvcResult project = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder()
                                .name("Transformation History Project")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        projectId = UUID.fromString(json(project).get("id").asText());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String base() {
        return "/api/v1/projects/" + projectId + "/transformations";
    }

    private UUID createTransformation(String template) throws Exception {
        MvcResult created = mockMvc.perform(post(base())
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"history-" + UUID.randomUUID().toString().substring(0, 8)
                                + "\",\"template\":" + objectMapper.writeValueAsString(template) + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(created).get("id").asText());
    }

    private void putTemplate(UUID id, String name, String template) throws Exception {
        mockMvc.perform(put(base() + "/" + id)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + objectMapper.writeValueAsString(name)
                                + ",\"template\":" + objectMapper.writeValueAsString(template) + "}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the template a transformation is created with is version 1 of its history")
    void createRecordsFirstVersion() throws Exception {
        UUID id = createTransformation(V1);

        mockMvc.perform(get(base() + "/" + id + "/versions").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].createdByEmail").value(email))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].restoredFromVersion").doesNotExist())
                // The list is an index, not a payload dump: the template comes from the
                // single-version endpoint, so a hundred 64 KB templates are not one response.
                .andExpect(jsonPath("$[0].template").doesNotExist());
    }

    @Test
    @DisplayName("each template edit is kept, newest first, and only the newest is current")
    void editsAccumulateNewestFirst() throws Exception {
        UUID id = createTransformation(V1);
        putTemplate(id, "edited-once", V2);
        putTemplate(id, "edited-twice", V3);

        mockMvc.perform(get(base() + "/" + id + "/versions").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].current").value(false))
                .andExpect(jsonPath("$[2].version").value(1));
    }

    @Test
    @DisplayName("a save that leaves the template alone publishes nothing — history is of the template")
    void renameAloneDoesNotVersion() throws Exception {
        UUID id = createTransformation(V1);

        // The edit form always PUTs the whole transformation back, template included, so
        // "renamed the transformation" and "rewrote the template" arrive as the same request.
        // The version counter used to go up for both, which is how a history would fill with
        // identical entries nobody made.
        putTemplate(id, "renamed-only", V1);

        mockMvc.perform(get(base() + "/" + id).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed-only"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get(base() + "/" + id + "/versions").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("one version reads back the exact template that was published")
    void oneVersionReadsBackItsTemplate() throws Exception {
        UUID id = createTransformation(V1);
        putTemplate(id, "edited", V2);

        mockMvc.perform(get(base() + "/" + id + "/versions/1").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.template").value(V1))
                .andExpect(jsonPath("$.current").value(false));

        mockMvc.perform(get(base() + "/" + id + "/versions/99").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("two versions are compared field by field, and each side is returned whole")
    void twoVersionsAreCompared() throws Exception {
        UUID id = createTransformation(V1);
        putTemplate(id, "edited", V2);

        mockMvc.perform(get(base() + "/" + id + "/versions/diff?left=1&right=2")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leftVersion").value(1))
                .andExpect(jsonPath("$.rightVersion").value(2))
                .andExpect(jsonPath("$.leftTemplate").value(V1))
                .andExpect(jsonPath("$.rightTemplate").value(V2))
                .andExpect(jsonPath("$.diffs.length()").value(2))
                .andExpect(jsonPath("$.diffs[?(@.path == '$.a')].type").value("CHANGED"))
                .andExpect(jsonPath("$.diffs[?(@.path == '$.b')].type").value("ADDED"));
    }

    @Test
    @DisplayName("restoring an old template publishes it as a new version instead of rewriting history")
    void restoreMovesForward() throws Exception {
        UUID id = createTransformation(V1);
        putTemplate(id, "edited", V2);

        mockMvc.perform(post(base() + "/" + id + "/versions/1/restore")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.template").value(V1));

        mockMvc.perform(get(base() + "/" + id + "/versions").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[0].restoredFromVersion").value(1))
                // v2 is still there: a restore is a step forward, not an erasure.
                .andExpect(jsonPath("$[1].version").value(2));

        mockMvc.perform(get(base() + "/" + id + "/versions/2").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value(V2));
    }

    @Test
    @DisplayName("restoring the version that is already current is refused, not recorded as a change")
    void restoringCurrentIsRefused() throws Exception {
        UUID id = createTransformation(V1);

        mockMvc.perform(post(base() + "/" + id + "/versions/1/restore")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already")));
    }

    @Test
    @DisplayName("a reader may read the history but not restore from it")
    void restoreNeedsWriteAccess() throws Exception {
        UUID id = createTransformation(V1);
        putTemplate(id, "edited", V2);

        MvcResult readOnlyKey = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"reader\",\"scope\":\"READ_ONLY\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String key = json(readOnlyKey).get("key").asText();

        mockMvc.perform(get(base() + "/" + id + "/versions").header("X-API-Key", key))
                .andExpect(status().isOk());

        mockMvc.perform(post(base() + "/" + id + "/versions/1/restore").header("X-API-Key", key))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("history is capped per transformation: the oldest versions fall off, the newest never do")
    void historyIsCapped() throws Exception {
        UUID id = createTransformation(V1);
        // Capped at 5 for this class (see @TestPropertySource) so this is 8 round trips, not 51.
        for (int i = 2; i <= 8; i++) {
            putTemplate(id, "capped", "{\"n\":" + i + "}");
        }

        mockMvc.perform(get(base() + "/" + id + "/versions").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].version").value(8))
                .andExpect(jsonPath("$[4].version").value(4));

        mockMvc.perform(get(base() + "/" + id + "/versions/1").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());
    }
}
