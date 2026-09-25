package com.webhook.platform.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.ApiKeyRequest;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RateLimitInfo;
import com.webhook.platform.api.dto.RateLimitResult;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class McpServerIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger RPC_ID = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID projectA;
    private String readWriteKeyA;
    private String readOnlyKeyA;
    private String readWriteKeyB;

    @DynamicPropertySource
    static void urlValidationProperties(DynamicPropertyRegistry registry) {
        registry.add("webhook.url-validation.allowed-hosts", () -> "mcp-a.example.com,mcp-b.example.com");
    }

    @BeforeEach
    void setup() throws Exception {
        when(redisRateLimiterService.tryAcquireWithInfo(any(UUID.class), anyInt())).thenReturn(
                RateLimitResult.builder().acquired(true).info(new RateLimitInfo(100, 99, 0L)).build());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String jwt = register("mcp-" + suffix + "@test.com", "MCP Org " + suffix);
        projectA = createProject(jwt, "MCP A " + suffix);
        UUID projectB = createProject(jwt, "MCP B " + suffix);

        readWriteKeyA = createKey(jwt, projectA, ApiKeyScope.READ_WRITE);
        readOnlyKeyA = createKey(jwt, projectA, ApiKeyScope.READ_ONLY);
        readWriteKeyB = createKey(jwt, projectB, ApiKeyScope.READ_WRITE);
    }

    @Test
    void completesTheStreamableHttpHandshake() throws Exception {
        JsonNode initialized = rpc(withBearer(readOnlyKeyA), "initialize", Map.of(
                "protocolVersion", "2025-11-25",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "railhook-mcp", "version", "test"))).get("result");
        assertThat(initialized.get("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(initialized.get("serverInfo").get("name").asText()).isEqualTo("railhook");
        assertThat(initialized.get("capabilities").has("tools")).isTrue();

        mockMvc.perform(withBearer(readOnlyKeyA).apply(mcpPost(objectMapper.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "method", "notifications/initialized")))))
                .andExpect(status().isAccepted());

        // Stateless: no server-initiated stream, which the client accepts.
        mockMvc.perform(get("/mcp")
                        .header("Authorization", "Bearer " + readOnlyKeyA)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isMethodNotAllowed());
    }

    // Clients probe newer methods; each 500 counted towards the API's 5xx alert.
    @Test
    void answersAnUnknownMethodWithAJsonRpcErrorNotA500() throws Exception {
        JsonNode response = rpc(withBearer(readOnlyKeyA), "server/discover", Map.of());

        assertThat(response.has("result")).isFalse();
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32601);
        assertThat(response.get("id").asInt()).isPositive();
    }

    @Test
    void listsTheToolsWithReadOnlyHintsOnTheReads() throws Exception {
        JsonNode result = rpc(withKey(readOnlyKeyA), "tools/list", Map.of()).get("result");

        List<String> names = new ArrayList<>();
        result.get("tools").forEach(tool -> names.add(tool.get("name").asText()));
        assertThat(names).containsExactlyInAnyOrder(
                "send_event", "list_endpoints", "create_endpoint", "list_subscriptions",
                "create_subscription", "list_deliveries", "get_delivery", "replay_delivery");

        for (JsonNode tool : result.get("tools")) {
            String name = tool.get("name").asText();
            boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(false);
            assertThat(readOnly)
                    .as("readOnlyHint of %s", name)
                    .isEqualTo(name.startsWith("list_") || name.startsWith("get_"));
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true))
                    .as("destructiveHint of %s", name)
                    .isFalse();
            assertThat(tool.path("inputSchema").path("properties").has("context"))
                    .as("the transport context is not an argument of %s", name)
                    .isFalse();
            if (name.equals("send_event")) {
                List<String> required = new ArrayList<>();
                tool.path("inputSchema").path("required").forEach(field -> required.add(field.asText()));
                assertThat(required).containsExactlyInAnyOrder("type", "data");
            }
        }
    }

    @Test
    void createsAndListsEndpointsWithAReadWriteKeyInEitherHeader() throws Exception {
        JsonNode created = callTool(withKey(readWriteKeyA), "create_endpoint",
                Map.of("url", "https://mcp-a.example.com/hook", "description", "from mcp"));
        assertThat(created.path("isError").asBoolean(false)).as(text(created)).isFalse();
        JsonNode endpoint = objectMapper.readTree(text(created));
        assertThat(endpoint.get("projectId").asText()).isEqualTo(projectA.toString());
        assertThat(endpoint.get("url").asText()).isEqualTo("https://mcp-a.example.com/hook");

        JsonNode listed = callTool(withBearer(readWriteKeyA), "list_endpoints", Map.of());
        assertThat(listed.path("isError").asBoolean(false)).as(text(listed)).isFalse();
        JsonNode page = objectMapper.readTree(text(listed));
        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("id").asText()).isEqualTo(endpoint.get("id").asText());
        assertThat(text(listed)).doesNotContain(readWriteKeyA);
    }

    @Test
    void refusesARequestWithoutAValidKey() throws Exception {
        mockMvc.perform(mcpPost(rpcBody("tools/list", Map.of())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mcpPost(rpcBody("tools/list", Map.of())).header("X-API-Key", "not-a-real-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mcpPost(rpcBody("tools/list", Map.of())).header("Authorization", "Bearer not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refusesEveryWriteToolForAReadOnlyKey() throws Exception {
        Map<String, Map<String, Object>> writes = Map.of(
                "create_endpoint", Map.of("url", "https://mcp-a.example.com/ro"),
                "create_subscription", Map.of("endpointId", UUID.randomUUID().toString(), "eventType", "order.created"),
                "send_event", Map.of("type", "order.created", "data", Map.of("id", 1)),
                "replay_delivery", Map.of("deliveryId", UUID.randomUUID().toString()));

        for (Map.Entry<String, Map<String, Object>> write : writes.entrySet()) {
            JsonNode result = callTool(withKey(readOnlyKeyA), write.getKey(), write.getValue());
            assertThat(result.path("isError").asBoolean(false)).as(write.getKey()).isTrue();
            assertThat(text(result)).as(write.getKey()).contains("READ_ONLY").contains("READ_WRITE");
        }

        JsonNode listed = callTool(withKey(readOnlyKeyA), "list_endpoints", Map.of());
        assertThat(objectMapper.readTree(text(listed)).get("totalElements").asInt()).isZero();
    }

    @Test
    void seesNothingOfAnotherProject() throws Exception {
        JsonNode endpoint = objectMapper.readTree(text(callTool(withKey(readWriteKeyA), "create_endpoint",
                Map.of("url", "https://mcp-a.example.com/private"))));
        String endpointId = endpoint.get("id").asText();
        assertThat(callTool(withKey(readWriteKeyA), "create_subscription",
                Map.of("endpointId", endpointId, "eventType", "order.created")).path("isError").asBoolean(false))
                .isFalse();

        JsonNode sent = objectMapper.readTree(text(callTool(withKey(readWriteKeyA), "send_event",
                Map.of("type", "order.created", "data", Map.of("orderId", 42)))));
        assertThat(sent.get("deliveriesCreated").asInt()).isEqualTo(1);

        JsonNode deliveries = objectMapper.readTree(text(callTool(withKey(readWriteKeyA), "list_deliveries",
                Map.of("eventId", sent.get("eventId").asText()))));
        String deliveryId = deliveries.get("content").get(0).get("id").asText();

        JsonNode own = callTool(withKey(readWriteKeyA), "get_delivery", Map.of("deliveryId", deliveryId));
        assertThat(own.path("isError").asBoolean(false)).as(text(own)).isFalse();
        assertThat(objectMapper.readTree(text(own)).get("delivery").get("id").asText()).isEqualTo(deliveryId);

        JsonNode foreignEndpoints = objectMapper.readTree(text(callTool(withKey(readWriteKeyB), "list_endpoints", Map.of())));
        assertThat(foreignEndpoints.get("totalElements").asInt()).isZero();
        JsonNode foreignSubscriptions = objectMapper.readTree(text(callTool(withKey(readWriteKeyB), "list_subscriptions", Map.of())));
        assertThat(foreignSubscriptions).isEmpty();
        JsonNode foreignDeliveries = objectMapper.readTree(text(callTool(withKey(readWriteKeyB), "list_deliveries", Map.of())));
        assertThat(foreignDeliveries.get("totalElements").asInt()).isZero();

        JsonNode foreignDelivery = callTool(withKey(readWriteKeyB), "get_delivery", Map.of("deliveryId", deliveryId));
        assertThat(foreignDelivery.path("isError").asBoolean(false)).isTrue();
        assertThat(text(foreignDelivery)).doesNotContain("order.created");

        JsonNode foreignReplay = callTool(withKey(readWriteKeyB), "replay_delivery", Map.of("deliveryId", deliveryId));
        assertThat(foreignReplay.path("isError").asBoolean(false)).isTrue();

        JsonNode foreignSubscribe = callTool(withKey(readWriteKeyB), "create_subscription",
                Map.of("endpointId", endpointId, "eventType", "order.created"));
        assertThat(foreignSubscribe.path("isError").asBoolean(false)).isTrue();
    }

    @Test
    void reportsAnInvalidArgumentAsAToolError() throws Exception {
        JsonNode result = callTool(withKey(readWriteKeyA), "send_event",
                Map.of("type", "Not A Valid Type", "data", Map.of()));
        assertThat(result.path("isError").asBoolean(false)).isTrue();
        assertThat(text(result)).contains("type");

        JsonNode badStatus = callTool(withKey(readWriteKeyA), "list_deliveries", Map.of("status", "NOPE"));
        assertThat(badStatus.path("isError").asBoolean(false)).isTrue();
        assertThat(text(badStatus)).contains("DLQ");
    }

    private interface Auth {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request);
    }

    private static Auth withKey(String key) {
        return request -> request.header("X-API-Key", key);
    }

    private static Auth withBearer(String key) {
        return request -> request.header("Authorization", "Bearer " + key);
    }

    private JsonNode callTool(Auth auth, String name, Map<String, Object> arguments) throws Exception {
        JsonNode response = rpc(auth, "tools/call", Map.of("name", name, "arguments", arguments));
        assertThat(response.has("result")).as(response.toString()).isTrue();
        return response.get("result");
    }

    private static String text(JsonNode callToolResult) {
        return callToolResult.get("content").get(0).get("text").asText();
    }

    private JsonNode rpc(Auth auth, String method, Map<String, Object> params) throws Exception {
        MvcResult result = mockMvc.perform(auth.apply(mcpPost(rpcBody(method, params))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String rpcBody(String method, Map<String, Object> params) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", RPC_ID.incrementAndGet(), "method", method, "params", params));
    }

    private static MockHttpServletRequestBuilder mcpPost(String body) {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
    }

    private String register(String email, String orgName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email(email).password("Test1234!").organizationName(orgName).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID createProject(String jwt, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder().name(name).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String createKey(String jwt, UUID projectId, ApiKeyScope scope) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder()
                                .name("mcp-" + scope).scope(scope).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }
}
