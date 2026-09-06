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

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three numbers the incidents page leads with, counted over the project rather than over
 * whatever fitted on the first page.
 *
 * <p>Only one of them used to be a real count. "Open" came from this endpoint; "Investigating"
 * and "Critical" were computed in the browser from `incidents.content` — one page of a filtered,
 * paginated list, twenty rows by default. So a project with more open incidents than fit on a
 * page showed "Critical: 0" while a critical incident sat on page two, and the three tiles sat
 * side by side looking like three answers to the same question.
 *
 * <p>Which is why this test creates more incidents than one page holds: counting them right on
 * a short list is not the property that broke.
 */
public class IncidentCountsIntegrationTest extends AbstractIntegrationTest {

    /** Above the page size the UI asks for, so a page-local count cannot pass this. */
    private static final int PAGE_SIZE = 20;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;
    private UUID projectId;

    @BeforeEach
    void setup() throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email("incident-counts-" + UUID.randomUUID() + "@test.com")
                                .password("Test1234!")
                                .organizationName("Incident Counts Org")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        token = objectMapper.readTree(registered.getResponse().getContentAsString()).get("accessToken").asText();

        MvcResult project = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder()
                                .name("Incident Counts Project")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        projectId = UUID.fromString(
                objectMapper.readTree(project.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createIncident(String title, String severity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/incidents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"severity\":\"" + severity + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void setStatus(UUID incidentId, String incidentStatus) throws Exception {
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + incidentStatus + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode counts() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/" + projectId + "/incidents/open-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    public void aProjectWithNoIncidentsCountsZeroOfEach() throws Exception {
        JsonNode counts = counts();

        // Not absent, not null: the tiles render these, and a missing number reads as a
        // broken tile rather than as a quiet project.
        assertThat(counts.get("count").asLong()).isZero();
        assertThat(counts.get("investigating").asLong()).isZero();
        assertThat(counts.get("critical").asLong()).isZero();
    }

    @Test
    public void everyCountSpansTheProjectAndNotOnePageOfIt() throws Exception {
        // Fill the first page with ordinary open incidents...
        for (int i = 0; i < PAGE_SIZE + 2; i++) {
            createIncident("Routine " + i, "WARNING");
        }
        // ...then put the interesting ones behind it. A count taken from the first page of the
        // list sees none of these.
        UUID critical = createIncident("Payments endpoint is down", "CRITICAL");
        UUID beingLookedAt = createIncident("Latency spike", "WARNING");
        setStatus(beingLookedAt, "INVESTIGATING");

        JsonNode counts = counts();

        assertThat(counts.get("count").asLong()).isEqualTo(PAGE_SIZE + 4);
        assertThat(counts.get("investigating").asLong()).isEqualTo(1);
        assertThat(counts.get("critical").asLong()).isEqualTo(1);

        // Resolving the critical one takes it out of all three, which is the whole point of
        // counting unresolved rather than counting rows.
        setStatus(critical, "RESOLVED");
        JsonNode after = counts();
        assertThat(after.get("critical").asLong()).isZero();
        assertThat(after.get("count").asLong()).isEqualTo(PAGE_SIZE + 3);
    }

    @Test
    public void investigatingIsCountedAsOpenToo() throws Exception {
        // "Open" here means not resolved. An incident somebody is actively working is the
        // last thing that should drop out of the number a badge shows.
        UUID incident = createIncident("Under investigation", "WARNING");
        setStatus(incident, "INVESTIGATING");

        JsonNode counts = counts();
        assertThat(counts.get("count").asLong()).isEqualTo(1);
        assertThat(counts.get("investigating").asLong()).isEqualTo(1);
    }

    @Test
    public void anotherProjectsIncidentsAreNotCountedHere() throws Exception {
        createIncident("Ours", "CRITICAL");

        MvcResult other = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder()
                                .name("Someone else's project")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID otherProjectId = UUID.fromString(
                objectMapper.readTree(other.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/projects/" + otherProjectId + "/incidents/open-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.critical").value(0));
    }
}
