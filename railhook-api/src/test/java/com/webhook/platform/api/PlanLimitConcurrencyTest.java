package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Free plan's project, endpoint and member limits under concurrent creates, against a real
 * Postgres.
 *
 * <p>Each limit was a count taken by {@code @RequireQuota} before the create's transaction began,
 * with nothing held between it and the insert, so requests released together at one below the
 * limit all counted below it and all got through. Only the active-tunnel limit took the
 * organization's row lock.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "billing.enabled=true")
class PlanLimitConcurrencyTest extends AbstractIntegrationTest {

    private static final int FREE_MAX_PROJECTS = 3;
    private static final int FREE_MAX_ENDPOINTS_PER_PROJECT = 5;
    private static final int FREE_MAX_MEMBERS = 5;
    private static final int ROUNDS = 5;
    private static final int RACERS = 4;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void concurrentProjectCreatesAtOneBelowTheLimitLetExactlyOneThrough() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String token = registerOwner();
            int existing = json(list("/api/v1/projects", token)).size();
            for (int i = existing; i < FREE_MAX_PROJECTS - 1; i++) {
                assertEquals(201, perform(createProject(token, "seed-" + i)));
            }

            int created = race(i -> createProject(token, "racer-" + i));

            assertEquals(1, created, "round " + round + ": exactly one create may take the last project slot");
            assertEquals(FREE_MAX_PROJECTS, json(list("/api/v1/projects", token)).size());
        }
    }

    @Test
    void concurrentEndpointCreatesAtOneBelowTheLimitLetExactlyOneThrough() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String token = registerOwner();
            UUID projectId = UUID.fromString(json(mockMvc.perform(createProject(token, "endpoints")).andReturn())
                    .get("id").asText());
            for (int i = 0; i < FREE_MAX_ENDPOINTS_PER_PROJECT - 1; i++) {
                assertEquals(201, perform(createEndpoint(token, projectId, i)));
            }

            int created = race(i -> createEndpoint(token, projectId, 100 + i));

            assertEquals(1, created, "round " + round + ": exactly one create may take the last endpoint slot");
            assertEquals(FREE_MAX_ENDPOINTS_PER_PROJECT,
                    json(list("/api/v1/projects/" + projectId + "/endpoints", token)).get("content").size());
        }
    }

    @Test
    void concurrentMemberAddsAtOneBelowTheLimitLetExactlyOneThrough() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String token = registerOwner();
            String orgId = json(list("/api/v1/auth/me", token)).get("organization").get("id").asText();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            // The owner is the first member.
            for (int i = 1; i < FREE_MAX_MEMBERS - 1; i++) {
                assertEquals(201, perform(addMember(token, orgId, "seed-" + i + "-" + tag + "@example.com")));
            }

            int added = race(i -> addMember(token, orgId, "racer-" + i + "-" + tag + "@example.com"));

            assertEquals(1, added, "round " + round + ": exactly one add may take the last member slot");
            assertEquals(FREE_MAX_MEMBERS, json(list("/api/v1/orgs/" + orgId + "/members", token)).size());
        }
    }

    private int race(IntFunction<RequestBuilder> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            CyclicBarrier together = new CyclicBarrier(RACERS);
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                int racer = i;
                statuses.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return perform(request.apply(racer));
                }));
            }
            int created = 0;
            for (Future<Integer> status : statuses) {
                if (status.get(60, TimeUnit.SECONDS) == 201) {
                    created++;
                }
            }
            return created;
        } finally {
            pool.shutdownNow();
        }
    }

    private int perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private RequestBuilder createProject(String token, String name) {
        try {
            return post("/api/v1/projects")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ProjectRequest.builder().name(name).build()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RequestBuilder createEndpoint(String token, UUID projectId, int n) {
        try {
            return post("/api/v1/projects/" + projectId + "/endpoints")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(EndpointRequest.builder()
                            .url("https://example.com/hook-" + n).enabled(true).build()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RequestBuilder addMember(String token, String orgId, String email) {
        try {
            return post("/api/v1/orgs/" + orgId + "/members")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(AddMemberRequest.builder()
                            .email(email).role(MembershipRole.VIEWER).build()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult list(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
    }

    private String registerOwner() throws Exception {
        RegisterRequest register = RegisterRequest.builder()
                .email("owner-" + UUID.randomUUID() + "@example.com")
                .password("Test1234!")
                .organizationName("Plan limit race")
                .build();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
