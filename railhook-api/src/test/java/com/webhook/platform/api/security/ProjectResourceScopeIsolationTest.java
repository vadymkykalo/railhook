package com.webhook.platform.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import com.webhook.platform.api.dto.ApiKeyRequest;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Looking the resource up in the org rather than the project once leaked B's signing secret.
public class ProjectResourceScopeIsolationTest extends AbstractIntegrationTest {

    private static final String B_URL = "https://prod.example.com/webhook";

    @DynamicPropertySource
    static void urlValidationProperties(DynamicPropertyRegistry registry) {
        registry.add("webhook.url-validation.allowed-hosts",
                () -> "prod.example.com,staging.example.com,attacker.example.com");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    private static String jwt;
    private static UUID projectA;
    private static UUID projectB;
    private static UUID organizationId;
    private static String keyA;

    @BeforeEach
    void setUpOrganizationOnce() throws Exception {
        if (jwt != null) {
            return;
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email("project-scope-" + suffix + "@test.com")
                                .password("Test1234!")
                                .organizationName("Project Scope " + suffix)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        jwt = json(registered).get("accessToken").asText();

        projectA = id(asJwt(post("/api/v1/projects"),
                objectMapper.writeValueAsString(ProjectRequest.builder().name("A " + suffix).build())));
        projectB = id(asJwt(post("/api/v1/projects"),
                objectMapper.writeValueAsString(ProjectRequest.builder().name("B " + suffix).build())));
        organizationId = projectRepository.findById(projectB).orElseThrow().getOrganizationId();

        keyA = json(asJwt(post("/api/v1/projects/" + projectA + "/api-keys"),
                objectMapper.writeValueAsString(ApiKeyRequest.builder()
                        .name("key-a").scope(ApiKeyScope.READ_WRITE).build())))
                .get("key").asText();
    }

    @Test
    void endpointOfAnotherProjectIsNotFound() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        expectNotFound(get(a("/endpoints/" + endpointB)));
    }

    @Test
    void rotatingAnotherProjectsEndpointSecretReturnsNoSecret() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        MvcResult result = asKey(post(a("/endpoints/" + endpointB + "/rotate-secret")), null);
        assertEquals(404, result.getResponse().getStatus());
        assertFalse(result.getResponse().getContentAsString().contains("\"secret\""),
                "A 404 must not carry a signing secret");
    }

    @Test
    void updatingAnotherProjectsEndpointLeavesItsUrlAlone() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        expectNotFound(put(a("/endpoints/" + endpointB)), "{\"url\":\"https://attacker.example.com/steal\"}");
        assertEquals(B_URL, json(asJwt(get(b("/endpoints/" + endpointB)), null)).get("url").asText());
    }

    @Test
    void deletingAnotherProjectsEndpointLeavesItInPlace() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        expectNotFound(delete(a("/endpoints/" + endpointB)));
        asJwt(get(b("/endpoints/" + endpointB)), null);
    }

    @Test
    void testingAnotherProjectsEndpointIsNotFound() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        expectNotFound(post(a("/endpoints/" + endpointB + "/test")));
    }

    @Test
    void verifyingAnotherProjectsEndpointIsNotFound() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        expectNotFound(post(a("/endpoints/" + endpointB + "/verify")));
    }

    @Test
    void skippingVerificationOfAnotherProjectsEndpointIsNotFound() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        String before = verificationStatusOf(endpointB);
        expectNotFound(post(a("/endpoints/" + endpointB + "/skip-verification")), "{\"reason\":\"x\"}");
        assertEquals(before, verificationStatusOf(endpointB));
    }

    private String verificationStatusOf(UUID endpointB) throws Exception {
        return json(asJwt(get(b("/endpoints/" + endpointB)), null)).get("verificationStatus").asText();
    }

    @Test
    void subscriptionOfAnotherProjectIsNotFound() throws Exception {
        UUID subscriptionB = createSubscription();
        expectNotFound(get(a("/subscriptions/" + subscriptionB)));
    }

    @Test
    void updatingAnotherProjectsSubscriptionIsNotFound() throws Exception {
        UUID subscriptionB = createSubscription();
        expectNotFound(put(a("/subscriptions/" + subscriptionB)), "{\"enabled\":false,\"eventType\":\"x.y\","
                + "\"endpointId\":\"" + createEndpoint(projectA, "https://staging.example.com/a") + "\"}");
        expectNotFound(patch(a("/subscriptions/" + subscriptionB)), "{\"enabled\":false}");
        assertTrue(json(asJwt(get(b("/subscriptions/" + subscriptionB)), null)).get("enabled").asBoolean());
    }

    @Test
    void deletingAnotherProjectsSubscriptionLeavesItInPlace() throws Exception {
        UUID subscriptionB = createSubscription();
        expectNotFound(delete(a("/subscriptions/" + subscriptionB)));
        asJwt(get(b("/subscriptions/" + subscriptionB)), null);
    }

    @Test
    void ruleOfAnotherProjectIsNotFoundForEveryOperation() throws Exception {
        UUID ruleB = id(asJwt(post(b("/rules")), "{\"name\":\"rule-" + UUID.randomUUID() + "\"}"));
        expectNotFound(get(a("/rules/" + ruleB)));
        expectNotFound(put(a("/rules/" + ruleB)), "{\"name\":\"hijacked\"}");
        expectNotFound(patch(a("/rules/" + ruleB + "/toggle")), "{\"enabled\":false}");
        expectNotFound(delete(a("/rules/" + ruleB)));
        JsonNode rule = json(asJwt(get(b("/rules/" + ruleB)), null));
        assertTrue(rule.get("enabled").asBoolean());
    }

    @Test
    void transformationOfAnotherProjectIsNotFoundForEveryOperation() throws Exception {
        UUID transformationB = createTransformation();
        expectNotFound(get(a("/transformations/" + transformationB)));
        expectNotFound(put(a("/transformations/" + transformationB)), "{\"name\":\"hijacked\",\"template\":\"{}\"}");
        expectNotFound(delete(a("/transformations/" + transformationB)));
        asJwt(get(b("/transformations/" + transformationB)), null);
    }

    @Test
    void previewCannotRenderAnotherProjectsTransformation() throws Exception {
        UUID transformationB = createTransformation();
        JsonNode preview = json(asKey(post(a("/transform-preview")),
                "{\"inputPayload\":\"{}\",\"transformationId\":\"" + transformationB + "\"}"));
        assertFalse(preview.get("success").asBoolean(), preview.toString());
        JsonNode dryRun = json(asKey(post(a("/transform-preview/delivery-dry-run")),
                "{\"payload\":\"{}\",\"transformationId\":\"" + transformationB + "\"}"));
        assertFalse(dryRun.get("success").asBoolean(), dryRun.toString());
    }

    @Test
    void eventTypeOfAnotherProjectIsNotFoundForEveryOperation() throws Exception {
        UUID eventTypeB = id(asJwt(post(b("/schemas")), "{\"name\":\"order." + letters() + "\"}"));
        UUID versionB = id(asJwt(post(b("/schemas/" + eventTypeB + "/versions")),
                "{\"schemaJson\":\"{\\\"type\\\":\\\"object\\\"}\"}"));
        UUID eventTypeA = id(asJwt(post(a("/schemas")), "{\"name\":\"order." + letters() + "\"}"));

        expectNotFound(get(a("/schemas/" + eventTypeB)));
        expectNotFound(put(a("/schemas/" + eventTypeB)), "{\"name\":\"hijacked\"}");
        expectNotFound(get(a("/schemas/" + eventTypeB + "/versions")));
        expectNotFound(post(a("/schemas/" + eventTypeB + "/versions")), "{\"schemaJson\":\"{}\"}");
        expectNotFound(get(a("/schemas/" + eventTypeB + "/versions/" + versionB)));
        expectNotFound(get(a("/schemas/" + eventTypeA + "/versions/" + versionB)));
        expectNotFound(post(a("/schemas/" + eventTypeB + "/versions/" + versionB + "/promote")));
        expectNotFound(post(a("/schemas/" + eventTypeA + "/versions/" + versionB + "/deprecate")));
        expectNotFound(get(a("/schemas/" + eventTypeB + "/changes")));
        expectNotFound(delete(a("/schemas/" + eventTypeB)));
        asJwt(get(b("/schemas/" + eventTypeB)), null);
    }

    @Test
    void workflowOfAnotherProjectIsNotFoundForEveryOperation() throws Exception {
        UUID workflowB = createWorkflow(projectB, "{\"nodes\":[],\"edges\":[]}");
        UUID executionB = executionRepository.save(WorkflowExecution.builder()
                .organizationId(organizationId).workflowId(workflowB)
                .status(ExecutionStatus.COMPLETED).depth(0).build()).getId();
        UUID workflowA = createWorkflow(projectA, "{\"nodes\":[],\"edges\":[]}");

        expectNotFound(get(a("/workflows/" + workflowB)));
        expectNotFound(put(a("/workflows/" + workflowB)), "{\"name\":\"hijacked\"}");
        expectNotFound(patch(a("/workflows/" + workflowB + "/toggle")), "{\"enabled\":true}");
        expectNotFound(post(a("/workflows/" + workflowB + "/trigger")), "{}");
        expectNotFound(get(a("/workflows/" + workflowB + "/executions")));
        expectNotFound(get(a("/workflows/" + workflowB + "/executions/" + executionB)));
        expectNotFound(get(a("/workflows/" + workflowA + "/executions/" + executionB)));
        expectNotFound(delete(a("/workflows/" + workflowB)));
        assertFalse(json(asJwt(get(b("/workflows/" + workflowB)), null)).get("enabled").asBoolean());
    }

    @Test
    void workflowCannotDeliverToAnotherProjectsEndpoint() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        String definition = "{\"nodes\":[{\"id\":\"n1\",\"type\":\"delivery\",\"data\":{\"endpointId\":\""
                + endpointB + "\"}}],\"edges\":[]}";
        expectNotFound(post(a("/workflows")), workflowBody(definition));

        UUID workflowA = createWorkflow(projectA, "{\"nodes\":[],\"edges\":[]}");
        expectNotFound(put(a("/workflows/" + workflowA)), workflowBody(definition));
    }

    @Test
    void workflowCannotEmitEventsIntoAnotherProject() throws Exception {
        String definition = "{\"nodes\":[{\"id\":\"n1\",\"type\":\"createEvent\",\"data\":{\"projectId\":\""
                + projectB + "\",\"eventType\":\"x.y\"}}],\"edges\":[]}";
        expectNotFound(post(a("/workflows")), workflowBody(definition));
    }

    @Test
    void incomingSourceOfAnotherProjectIsNotFoundForEveryOperation() throws Exception {
        UUID sourceB = createSource(projectB);
        expectNotFound(get(a("/incoming-sources/" + sourceB)));
        expectNotFound(put(a("/incoming-sources/" + sourceB)), "{\"name\":\"hijacked\"}");
        expectNotFound(delete(a("/incoming-sources/" + sourceB)));
        assertFalse(json(asJwt(get(b("/incoming-sources/" + sourceB)), null)).get("name").asText()
                .equals("hijacked"));
    }

    @Test
    void destinationsOfAnotherProjectsSourceAreNotFound() throws Exception {
        UUID sourceB = createSource(projectB);
        expectNotFound(get(a("/incoming-sources/" + sourceB + "/destinations")));
        expectNotFound(post(a("/incoming-sources/" + sourceB + "/destinations")),
                "{\"url\":\"https://attacker.example.com/steal\"}");
        assertEquals(0, json(asJwt(get(b("/incoming-sources/" + sourceB + "/destinations")), null))
                .get("content").size());
    }

    @Test
    void destinationOfAnotherProjectIsNotFoundForEveryOperation() throws Exception {
        UUID sourceB = createSource(projectB);
        UUID destinationB = id(asJwt(post(b("/incoming-sources/" + sourceB + "/destinations")),
                "{\"url\":\"" + B_URL + "\"}"));
        UUID sourceA = createSource(projectA);

        for (UUID source : new UUID[]{sourceA, sourceB}) {
            String path = "/incoming-sources/" + source + "/destinations/" + destinationB;
            expectNotFound(get(a(path)));
            expectNotFound(put(a(path)), "{\"url\":\"https://attacker.example.com/steal\"}");
            expectNotFound(delete(a(path)));
        }
        assertEquals(B_URL, json(asJwt(get(b("/incoming-sources/" + sourceB + "/destinations/" + destinationB)),
                null)).get("url").asText());
    }

    @Test
    void dlqItemOfAnotherProjectIsNotFound() throws Exception {
        UUID deliveryB = seedDelivery(DeliveryStatus.DLQ).getId();
        expectNotFound(get(a("/dlq/" + deliveryB)));
    }

    @Test
    void deliveryListFilteredByAnotherProjectsEventIsEmpty() throws Exception {
        Delivery deliveryB = seedDelivery(DeliveryStatus.SUCCESS);
        JsonNode page = json(asKey(get("/api/v1/deliveries/projects/" + projectA + "?eventId=" + deliveryB.getEventId()),
                null));
        assertEquals(0, page.get("content").size(), page.toString());
    }

    @Test
    void ownProjectsResourcesStillWorkThroughTheKey() throws Exception {
        UUID endpointA = createEndpoint(projectA, "https://staging.example.com/webhook");
        assertEquals(200, asKey(get(a("/endpoints/" + endpointA)), null).getResponse().getStatus());
        MvcResult rotated = asKey(post(a("/endpoints/" + endpointA + "/rotate-secret")), null);
        assertEquals(200, rotated.getResponse().getStatus());
        assertFalse(json(rotated).get("secret").asText().isBlank());
        assertEquals(200, asKey(put(a("/endpoints/" + endpointA)),
                "{\"url\":\"https://staging.example.com/moved\"}").getResponse().getStatus());

        UUID sourceA = createSource(projectA);
        UUID destinationA = id(asKey(post(a("/incoming-sources/" + sourceA + "/destinations")),
                "{\"url\":\"https://staging.example.com/in\"}"));
        assertEquals(200, asKey(get(a("/incoming-sources/" + sourceA + "/destinations/" + destinationA)), null)
                .getResponse().getStatus());

        UUID workflowA = createWorkflow(projectA, "{\"nodes\":[{\"id\":\"n1\",\"type\":\"delivery\","
                + "\"data\":{\"endpointId\":\"" + endpointA + "\"}}],\"edges\":[]}");
        assertEquals(200, asKey(get(a("/workflows/" + workflowA)), null).getResponse().getStatus());

        UUID eventTypeA = id(asKey(post(a("/schemas")), "{\"name\":\"own." + letters() + "\"}"));
        assertEquals(200, asKey(get(a("/schemas/" + eventTypeA)), null).getResponse().getStatus());
    }

    private String a(String path) {
        return "/api/v1/projects/" + projectA + path;
    }

    private String b(String path) {
        return "/api/v1/projects/" + projectB + path;
    }

    private void expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
        expectNotFound(request, null);
    }

    private void expectNotFound(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = asKey(request, body);
        assertEquals(404, result.getResponse().getStatus(),
                result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
                        + " → " + result.getResponse().getContentAsString());
    }

    private MvcResult asKey(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("X-API-Key", keyA);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult asJwt(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status >= 200 && status < 300,
                result.getRequest().getRequestURI() + " → " + status + " " + result.getResponse().getContentAsString());
        return result;
    }

    // Event type names allow lowercase letters, dots and underscores only.
    private static String letters() {
        return UUID.randomUUID().toString().replaceAll("[^a-f]", "");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID id(MvcResult result) throws Exception {
        return UUID.fromString(json(result).get("id").asText());
    }

    private UUID createEndpoint(UUID project, String url) throws Exception {
        return id(asJwt(post("/api/v1/projects/" + project + "/endpoints"), "{\"url\":\"" + url + "\"}"));
    }

    private UUID createSubscription() throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        return id(asJwt(post(b("/subscriptions")),
                "{\"endpointId\":\"" + endpointB + "\",\"eventType\":\"order.created\"}"));
    }

    private UUID createTransformation() throws Exception {
        return id(asJwt(post(b("/transformations")),
                "{\"name\":\"t-" + UUID.randomUUID() + "\",\"template\":\"{\\\"secret\\\":\\\"b-only\\\"}\"}"));
    }

    private UUID createSource(UUID project) throws Exception {
        return id(asJwt(post("/api/v1/projects/" + project + "/incoming-sources"),
                "{\"name\":\"src-" + UUID.randomUUID() + "\"}"));
    }

    private UUID createWorkflow(UUID project, String definition) throws Exception {
        return id(asJwt(post("/api/v1/projects/" + project + "/workflows"), workflowBody(definition)));
    }

    private String workflowBody(String definition) {
        return "{\"name\":\"wf-" + UUID.randomUUID() + "\",\"definition\":" + definition + "}";
    }

    private Delivery seedDelivery(DeliveryStatus status) throws Exception {
        UUID endpointB = createEndpoint(projectB, B_URL);
        Event event = eventRepository.save(Event.builder()
                .organizationId(organizationId).projectId(projectB)
                .eventType("order.created").payload("{}").build());
        return deliveryRepository.saveAndFlush(Delivery.builder()
                .organizationId(organizationId).eventId(event.getId()).endpointId(endpointB)
                .status(status).build());
    }
}
