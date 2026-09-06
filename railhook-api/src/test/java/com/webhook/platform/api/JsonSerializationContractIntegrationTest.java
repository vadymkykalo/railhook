package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Three properties of the JSON this API exchanges, held down because Spring Boot 4 moved the
 * floor they stood on.
 *
 * <p>Boot 4 serializes HTTP through Jackson 3. This project holds it on Jackson 2 with two
 * things that look like housekeeping and are not: a bridge module Spring has already deprecated,
 * and {@code spring.http.converters.preferred-json-mapper} in {@code application.yml}. Why
 * Jackson 2 at all is written on that dependency in the pom — briefly, one of the two DTOs that
 * would otherwise have to change is backed by a JSONB column.
 *
 * <p>The two directions fail separately, which is the reason both are here. Take the property
 * away and *reading* a body into a Jackson 2 {@code JsonNode} answers 500 with a type definition
 * error, while writing one still works — so a test that only checked responses would have called
 * the property dead configuration and invited its removal. {@code EventIngestRequest.data} is
 * such a field, so the direction that breaks first is every event the platform ingests.
 *
 * <p>The timestamp case guards something the mappers happen to agree on today and would not
 * announce if that changed: the difference between {@code "2026-09-04T15:04:05Z"} and
 * {@code 1757000645.123} is every SDK, the generated TypeScript types and every customer
 * integration breaking at once. The type check fails loudly — "expected String, got Double" says
 * why in one line; the pattern catches the quieter version, still a string but a local time, or
 * one without the {@code Z} that OpenAPI's {@code format: date-time} promises.
 */
@AutoConfigureMockMvc
public class JsonSerializationContractIntegrationTest extends AbstractIntegrationTest {

    /** ISO-8601 with a UTC designator, which is what OpenAPI's {@code format: date-time} means. */
    private static final String ISO_8601_UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void aRequestBodyIsReadIntoAJsonNodeField() throws Exception {
        String token = registerAndReturnAccessToken("json-contract-ingest@example.com");
        String projectId = createProject(token);

        // Nested object and array on purpose: the failure is in resolving the JsonNode type at
        // all, but a payload with structure is what a customer actually sends.
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/events/test")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"contract.test\","
                                + "\"data\":{\"nested\":{\"n\":1},\"arr\":[1,2,3]}}"))
                .andExpect(status().isCreated());
    }

    @Test
    public void aResponseBodyCarryingAJsonNodeFieldSerializes() throws Exception {
        // The plan catalog is public and seeded by migration, so this needs no fixture — and
        // PlanResponse.features is the JSONB-backed JsonNode of the pair.
        mockMvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].features").exists())
                .andExpect(jsonPath("$[0].features").isMap());
    }

    @Test
    public void aResourceTimestampIsAnIsoStringNotAnEpochNumber() throws Exception {
        String token = registerAndReturnAccessToken("json-contract@example.com");

        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdAt").value(instanceOf(String.class)))
                .andExpect(jsonPath("$[0].createdAt").value(matchesPattern(ISO_8601_UTC)));
    }

    private String registerAndReturnAccessToken(String email) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName("Contract Co")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();
    }

    private String createProject(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Contract Project\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
