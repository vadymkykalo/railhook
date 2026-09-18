package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.ApiKeyRequest;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RateLimitInfo;
import com.webhook.platform.api.dto.RateLimitResult;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.service.billing.QuotaCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/events against a month's quota that the last accepted Event used up.
 *
 * <p>The quota was checked by an aspect on the controller, before the service looked the
 * Idempotency-Key up — so a client that lost the answer to the Event which took the last slot and
 * retried it, as the key exists for, was told it was over quota for an Event already accepted.
 */
@TestPropertySource(properties = "billing.enabled=true")
class EventIngestQuotaIntegrationTest extends AbstractIntegrationTest {

    private static final long FREE_MAX_EVENTS_PER_MONTH = 10_000L;

    @MockitoBean
    private QuotaCounterService quotaCounterService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String apiKey;

    @BeforeEach
    void setUp() throws Exception {
        when(redisRateLimiterService.tryAcquireWithInfo(any(UUID.class), anyInt())).thenReturn(
                RateLimitResult.builder().acquired(true).info(new RateLimitInfo(100, 99, 0L)).build());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String jwt = read(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email("quota-" + suffix + "@example.com")
                                .password("Test1234!")
                                .organizationName("Quota Co " + suffix)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn()).get("accessToken").asText();

        String projectId = read(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder().name("Quota").build())))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();

        apiKey = read(mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder()
                                .name("quota-key")
                                .scope(ApiKeyScope.READ_WRITE)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn()).get("key").asText();
    }

    @Test
    void theRetryOfTheEventThatTookTheLastSlotGetsThatEventBack() throws Exception {
        when(quotaCounterService.getCurrentCount()).thenReturn(FREE_MAX_EVENTS_PER_MONTH - 1);
        String accepted = read(ingest("last-slot").andExpect(status().isCreated()).andReturn())
                .get("eventId").asText();

        when(quotaCounterService.getCurrentCount()).thenReturn(FREE_MAX_EVENTS_PER_MONTH);
        String retried = read(ingest("last-slot").andExpect(status().isCreated()).andReturn())
                .get("eventId").asText();

        assertThat(retried).isEqualTo(accepted);
    }

    @Test
    void aNewEventOverTheQuotaIsStillRefused() throws Exception {
        when(quotaCounterService.getCurrentCount()).thenReturn(FREE_MAX_EVENTS_PER_MONTH);

        ingest("never-accepted").andExpect(status().isPaymentRequired());
    }

    private ResultActions ingest(String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/events")
                .header("X-API-Key", apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"order.created\",\"data\":{\"id\":1}}"));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
