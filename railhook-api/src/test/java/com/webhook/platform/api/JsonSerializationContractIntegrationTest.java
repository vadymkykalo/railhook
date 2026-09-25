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

// Boot 4 defaults to Jackson 3; this pins the Jackson 2 bridge and preferred-json-mapper.
@AutoConfigureMockMvc
public class JsonSerializationContractIntegrationTest extends AbstractIntegrationTest {

    private static final String ISO_8601_UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void aRequestBodyIsReadIntoAJsonNodeField() throws Exception {
        String token = registerAndReturnAccessToken("json-contract-ingest@example.com");
        String projectId = createProject(token);

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/events/test")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"contract.test\","
                                + "\"data\":{\"nested\":{\"n\":1},\"arr\":[1,2,3]}}"))
                .andExpect(status().isCreated());
    }

    @Test
    public void aResponseBodyCarryingAJsonNodeFieldSerializes() throws Exception {
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
