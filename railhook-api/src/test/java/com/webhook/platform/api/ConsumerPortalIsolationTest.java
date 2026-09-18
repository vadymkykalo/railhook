package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.PortalSession;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.PortalSessionRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.dto.ApiKeyRequest;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A portal session is a bearer token handed to someone who is not a Railhook user at all: the
 * customer's own Consumer. Everything it reaches must be that Consumer's — never a sibling
 * Consumer's, never the project's unassigned endpoints, never another project's or another
 * organization's — and it must reach nothing outside {@code /api/v1/portal/**}.
 *
 * <p>Billing is on so the Free plan's endpoint quota is real: an endpoint a Consumer creates is
 * still one of the project's endpoints.
 */
@TestPropertySource(properties = "billing.enabled=true")
public class ConsumerPortalIsolationTest extends AbstractIntegrationTest {

    private static final String OWN_URL = "https://consumer.example.com/hook";
    private static final String OTHER_URL = "https://other.example.com/hook";
    private static final int FREE_MAX_ENDPOINTS_PER_PROJECT = 5;

    @DynamicPropertySource
    static void urlValidationProperties(DynamicPropertyRegistry registry) {
        registry.add("webhook.url-validation.allowed-hosts",
                () -> "consumer.example.com,other.example.com,attacker.example.com");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private PortalSessionRepository portalSessionRepository;

    private static String jwt;
    private static String otherOrgJwt;
    private static String keyA;
    private static UUID organizationId;
    private static UUID projectA;
    private static UUID projectB;
    private static UUID otherOrgProject;
    private static UUID consumerOne;
    private static UUID consumerTwo;
    private static UUID consumerInProjectB;
    private static UUID consumerInOtherOrg;

    /** Endpoints a test made in project A, retired after it so the Free plan's five never fill up. */
    private final List<UUID> endpointsToRetire = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        when(redisRateLimiterService.tryAcquireForPortalSession(any(UUID.class), anyInt())).thenReturn(true);
        if (jwt != null) {
            return;
        }
        jwt = register("portal-owner");
        otherOrgJwt = register("portal-other-org");

        projectA = createProject(jwt, "Portal A");
        projectB = createProject(jwt, "Portal B");
        otherOrgProject = createProject(otherOrgJwt, "Elsewhere");
        organizationId = projectRepository.findById(projectA).orElseThrow().getOrganizationId();

        keyA = json(ok(withJwt(post("/api/v1/projects/" + projectA + "/api-keys"), jwt,
                objectMapper.writeValueAsString(ApiKeyRequest.builder()
                        .name("backend").scope(ApiKeyScope.READ_WRITE).build()))))
                .get("key").asText();

        consumerOne = createConsumer(projectA, keyA, null, "user-1", "Acme Ltd");
        consumerTwo = createConsumer(projectA, keyA, null, "user-2", "Globex");
        consumerInProjectB = createConsumer(projectB, null, jwt, "user-1", "Acme in B");
        consumerInOtherOrg = createConsumer(otherOrgProject, null, otherOrgJwt, "user-1", "Initech");
    }

    @AfterEach
    void retireEndpoints() {
        endpointsToRetire.forEach(id -> endpointRepository.findById(id).ifPresent(endpoint -> {
            endpoint.setDeletedAt(Instant.now());
            endpointRepository.saveAndFlush(endpoint);
        }));
    }

    // ── The token ──

    @Test
    void theTokenIsReturnedOnceAndStoredOnlyAsItsHash() throws Exception {
        JsonNode session = json(ok(withKey(post(consumerPath(projectA, consumerOne) + "/portal-sessions"),
                "{\"ttlMinutes\":30}")));
        String token = session.get("token").asText();
        String url = session.get("url").asText();

        assertTrue(token.startsWith("rhp_"), token);
        assertTrue(url.endsWith("/portal#" + token), url);

        PortalSession stored = portalSessionRepository.findByTokenHash(CryptoUtils.hashApiKey(token)).orElseThrow();
        assertEquals(consumerOne, stored.getConsumerId());
        assertEquals(projectA, stored.getProjectId());
        assertNotEquals(token, stored.getTokenHash());
        assertTrue(stored.getExpiresAt().isAfter(Instant.now().plusSeconds(29 * 60)));
        assertTrue(stored.getExpiresAt().isBefore(Instant.now().plusSeconds(31 * 60)));
    }

    @Test
    void theSessionDescribesItsConsumerAndProject() throws Exception {
        JsonNode session = json(ok(asPortal(get("/api/v1/portal/session"), portalToken(projectA, consumerOne), null)));
        assertEquals("Acme Ltd", session.get("consumerName").asText());
        assertEquals("Portal A", session.get("projectName").asText());
        assertTrue(session.get("eventTypes").isArray());
    }

    @Test
    void anAllowedOriginMustBeAnHttpsOrigin() throws Exception {
        for (String origin : List.of("http://app.example.com", "https://app.example.com/path", "javascript:alert(1)")) {
            MvcResult result = withKey(post(consumerPath(projectA, consumerOne) + "/portal-sessions"),
                    "{\"allowedOrigin\":\"" + origin + "\"}");
            assertEquals(400, result.getResponse().getStatus(), origin);
        }
        JsonNode session = json(ok(withKey(post(consumerPath(projectA, consumerOne) + "/portal-sessions"),
                "{\"allowedOrigin\":\"https://app.example.com\"}")));
        assertEquals("https://app.example.com", session.get("allowedOrigin").asText());
        assertTrue(session.get("url").asText().contains("?origin=https://app.example.com#"),
                session.get("url").asText());
    }

    @Test
    void aTtlOutsideTheAllowedRangeIsRefused() throws Exception {
        assertEquals(400, withKey(post(consumerPath(projectA, consumerOne) + "/portal-sessions"),
                "{\"ttlMinutes\":1441}").getResponse().getStatus());
        assertEquals(400, withKey(post(consumerPath(projectA, consumerOne) + "/portal-sessions"),
                "{\"ttlMinutes\":0}").getResponse().getStatus());
    }

    @Test
    void anExpiredSessionIsUnauthorized() throws Exception {
        String token = portalToken(projectA, consumerOne);
        PortalSession stored = portalSessionRepository.findByTokenHash(CryptoUtils.hashApiKey(token)).orElseThrow();
        stored.setExpiresAt(Instant.now().minusSeconds(1));
        portalSessionRepository.saveAndFlush(stored);

        assertEquals(401, asPortal(get("/api/v1/portal/session"), token, null).getResponse().getStatus());
    }

    @Test
    void anUnknownOrMissingTokenIsUnauthorized() throws Exception {
        assertEquals(401, asPortal(get("/api/v1/portal/session"), "rhp_not-a-real-token", null)
                .getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get("/api/v1/portal/session")).andReturn().getResponse().getStatus());
    }

    @Test
    void revokedSessionsAuthenticateNothing() throws Exception {
        String token = portalToken(projectA, consumerTwo);
        ok(asPortal(get("/api/v1/portal/session"), token, null));

        assertEquals(204, withKey(delete(consumerPath(projectA, consumerTwo) + "/portal-sessions"), null)
                .getResponse().getStatus());

        assertEquals(401, asPortal(get("/api/v1/portal/session"), token, null).getResponse().getStatus());
    }

    @Test
    void aPortalTokenOpensNothingOutsideThePortal() throws Exception {
        String token = portalToken(projectA, consumerOne);
        for (String path : List.of(
                "/api/v1/projects/" + projectA + "/endpoints",
                "/api/v1/projects/" + projectA + "/consumers",
                "/api/v1/deliveries/projects/" + projectA,
                "/api/v1/auth/me")) {
            assertEquals(401, asPortal(get(path), token, null).getResponse().getStatus(), path);
        }
    }

    @Test
    void dashboardCredentialsDoNotOpenThePortal() throws Exception {
        assertEquals(403, withKey(get("/api/v1/portal/session"), null).getResponse().getStatus());
        assertEquals(403, withJwt(get("/api/v1/portal/session"), jwt, null).getResponse().getStatus());
    }

    // ── Endpoints ──

    @Test
    void anEndpointCreatedThroughThePortalBelongsToTheConsumer() throws Exception {
        String token = portalToken(projectA, consumerOne);
        JsonNode created = json(ok(asPortal(post("/api/v1/portal/endpoints"), token,
                "{\"url\":\"" + OWN_URL + "\",\"description\":\"Orders\",\"eventTypes\":[\"order.created\",\"order.paid\"]}")));

        assertFalse(created.get("secret").asText().isBlank(), "the secret is shown once, at creation");
        UUID endpointId = UUID.fromString(created.get("id").asText());
        endpointsToRetire.add(endpointId);

        Endpoint stored = endpointRepository.findById(endpointId).orElseThrow();
        assertEquals(consumerOne, stored.getConsumerId());
        assertEquals(projectA, stored.getProjectId());
        assertEquals(List.of("order.created", "order.paid"), subscriptionRepository.findByProjectId(projectA).stream()
                .filter(s -> s.getEndpointId().equals(endpointId)).map(s -> s.getEventType()).sorted().toList());

        JsonNode listed = json(ok(asPortal(get("/api/v1/portal/endpoints"), token, null)));
        assertTrue(ids(listed).contains(endpointId.toString()));
        assertTrue(listed.get(0).get("secret") == null || listed.get(0).get("secret").isNull(),
                "a list never carries a secret");

        JsonNode fromBackend = json(ok(withKey(get(consumerPath(projectA, consumerOne) + "/endpoints"), null)));
        assertTrue(ids(fromBackend).contains(endpointId.toString()));

        JsonNode updated = json(ok(asPortal(put("/api/v1/portal/endpoints/" + endpointId), token,
                "{\"url\":\"" + OWN_URL + "/v2\",\"eventTypes\":[\"order.paid\"],\"enabled\":false}")));
        assertEquals(OWN_URL + "/v2", updated.get("url").asText());
        assertFalse(updated.get("enabled").asBoolean());
        assertEquals(List.of("order.paid"), textValues(updated.get("eventTypes")));

        JsonNode rotated = json(ok(asPortal(post("/api/v1/portal/endpoints/" + endpointId + "/rotate-secret"), token, null)));
        assertNotEquals(created.get("secret").asText(), rotated.get("secret").asText());

        assertEquals(204, asPortal(delete("/api/v1/portal/endpoints/" + endpointId), token, null)
                .getResponse().getStatus());
        assertEquals(404, asPortal(get("/api/v1/portal/endpoints/" + endpointId), token, null)
                .getResponse().getStatus());
    }

    @Test
    void aConsumerCannotSeeOrTouchAnotherConsumersEndpoint() throws Exception {
        UUID siblingEndpoint = createEndpointFor(projectA, consumerTwo, OTHER_URL);
        String token = portalToken(projectA, consumerOne);

        assertFalse(ids(json(ok(asPortal(get("/api/v1/portal/endpoints"), token, null))))
                .contains(siblingEndpoint.toString()));
        expectPortalNotFound(get("/api/v1/portal/endpoints/" + siblingEndpoint), token, null);
        expectPortalNotFound(put("/api/v1/portal/endpoints/" + siblingEndpoint), token,
                "{\"url\":\"https://attacker.example.com/steal\"}");
        expectPortalNotFound(post("/api/v1/portal/endpoints/" + siblingEndpoint + "/rotate-secret"), token, null);
        expectPortalNotFound(delete("/api/v1/portal/endpoints/" + siblingEndpoint), token, null);

        Endpoint untouched = endpointRepository.findById(siblingEndpoint).orElseThrow();
        assertEquals(OTHER_URL, untouched.getUrl());
        assertNull(untouched.getDeletedAt());
    }

    @Test
    void theProjectsUnassignedEndpointsAreNotAnyConsumers() throws Exception {
        UUID unassigned = createEndpointFor(projectA, null, OTHER_URL);
        String token = portalToken(projectA, consumerOne);

        assertFalse(ids(json(ok(asPortal(get("/api/v1/portal/endpoints"), token, null))))
                .contains(unassigned.toString()));
        expectPortalNotFound(get("/api/v1/portal/endpoints/" + unassigned), token, null);
        expectPortalNotFound(delete("/api/v1/portal/endpoints/" + unassigned), token, null);
    }

    @Test
    void anotherProjectsAndAnotherOrganizationsConsumersSeeNothingOfThisOne() throws Exception {
        UUID endpoint = createEndpointFor(projectA, consumerOne, OWN_URL);

        for (String token : List.of(portalToken(projectB, consumerInProjectB),
                portalTokenAs(otherOrgProject, consumerInOtherOrg, otherOrgJwt))) {
            assertFalse(ids(json(ok(asPortal(get("/api/v1/portal/endpoints"), token, null))))
                    .contains(endpoint.toString()));
            expectPortalNotFound(get("/api/v1/portal/endpoints/" + endpoint), token, null);
            expectPortalNotFound(put("/api/v1/portal/endpoints/" + endpoint), token,
                    "{\"url\":\"https://attacker.example.com/steal\"}");
            expectPortalNotFound(delete("/api/v1/portal/endpoints/" + endpoint), token, null);
        }
        assertEquals(OWN_URL, endpointRepository.findById(endpoint).orElseThrow().getUrl());
    }

    @Test
    void anEndpointCanOnlyBeAssignedToAConsumerOfItsOwnProject() throws Exception {
        MvcResult result = withJwt(post("/api/v1/projects/" + projectA + "/endpoints"), jwt,
                "{\"url\":\"" + OWN_URL + "\",\"consumerId\":\"" + consumerInProjectB + "\"}");
        assertEquals(404, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    @Test
    void anApiKeyManagesConsumersOfItsOwnProjectOnly() throws Exception {
        assertEquals(403, withKey(get("/api/v1/projects/" + projectB + "/consumers"), null).getResponse().getStatus());
        assertEquals(403, withKey(post(consumerPath(projectB, consumerInProjectB) + "/portal-sessions"), "{}")
                .getResponse().getStatus());
        assertEquals(404, withKey(post(consumerPath(projectA, consumerInProjectB) + "/portal-sessions"), "{}")
                .getResponse().getStatus());
    }

    @Test
    void anExternalIdIsUniqueWithinAProject() throws Exception {
        MvcResult duplicate = withKey(post("/api/v1/projects/" + projectA + "/consumers"),
                "{\"externalId\":\"user-1\",\"name\":\"Again\"}");
        assertEquals(409, duplicate.getResponse().getStatus());

        JsonNode found = json(ok(withKey(get("/api/v1/projects/" + projectA + "/consumers?externalId=user-1"), null)));
        assertEquals(1, found.get("content").size());
        assertEquals(consumerOne.toString(), found.get("content").get(0).get("id").asText());
    }

    // ── Deliveries ──

    @Test
    void deliveriesAttemptsAndRetriesAreConfinedToTheConsumersEndpoints() throws Exception {
        UUID ownEndpoint = createEndpointFor(projectA, consumerOne, OWN_URL);
        UUID siblingEndpoint = createEndpointFor(projectA, consumerTwo, OTHER_URL);
        Delivery own = seedDelivery(ownEndpoint, DeliveryStatus.FAILED);
        Delivery sibling = seedDelivery(siblingEndpoint, DeliveryStatus.FAILED);
        String token = portalToken(projectA, consumerOne);

        JsonNode page = json(ok(asPortal(get("/api/v1/portal/deliveries"), token, null)));
        List<String> listed = ids(page.get("content"));
        assertTrue(listed.contains(own.getId().toString()), page.toString());
        assertFalse(listed.contains(sibling.getId().toString()), page.toString());
        assertEquals("order.created", page.get("content").get(0).get("eventType").asText());

        assertEquals(0, json(ok(asPortal(get("/api/v1/portal/deliveries?status=SUCCESS"), token, null)))
                .get("content").size());
        assertEquals(0, json(ok(asPortal(get("/api/v1/portal/deliveries?endpointId=" + siblingEndpoint), token, null)))
                .get("content").size());

        expectPortalNotFound(get("/api/v1/portal/deliveries/" + sibling.getId() + "/attempts"), token, null);
        expectPortalNotFound(post("/api/v1/portal/deliveries/" + sibling.getId() + "/retry"), token, null);
        assertEquals(DeliveryStatus.FAILED, deliveryRepository.findById(sibling.getId()).orElseThrow().getStatus());

        ok(asPortal(get("/api/v1/portal/deliveries/" + own.getId() + "/attempts"), token, null));
        assertEquals(202, asPortal(post("/api/v1/portal/deliveries/" + own.getId() + "/retry"), token, null)
                .getResponse().getStatus());
        assertEquals(DeliveryStatus.PENDING, deliveryRepository.findById(own.getId()).orElseThrow().getStatus());
    }

    // ── Lifecycle ──

    @Test
    void deletingAConsumerRemovesItsEndpointsAndEndsItsSessions() throws Exception {
        UUID consumer = createConsumer(projectA, keyA, null, "leaving-" + UUID.randomUUID(), "Leaving");
        UUID endpoint = createEndpointFor(projectA, consumer, OWN_URL);
        String token = portalToken(projectA, consumer);

        assertEquals(204, withKey(delete(consumerPath(projectA, consumer)), null).getResponse().getStatus());

        assertEquals(401, asPortal(get("/api/v1/portal/session"), token, null).getResponse().getStatus());
        assertTrue(endpointRepository.findById(endpoint).orElseThrow().getDeletedAt() != null,
                "a deleted consumer's endpoints stop receiving");
        assertEquals(404, withKey(get(consumerPath(projectA, consumer)), null).getResponse().getStatus());
    }

    // ── Quota ──

    @Test
    void portalEndpointsCountAgainstTheProjectsEndpointQuota() throws Exception {
        UUID project = createProject(otherOrgJwt, "Quota");
        UUID consumer = createConsumer(project, null, otherOrgJwt, "quota-user", "Quota");
        for (int i = 0; i < FREE_MAX_ENDPOINTS_PER_PROJECT - 1; i++) {
            ok(withJwt(post("/api/v1/projects/" + project + "/endpoints"), otherOrgJwt,
                    "{\"url\":\"" + OWN_URL + "/" + i + "\"}"));
        }
        String token = portalTokenAs(project, consumer, otherOrgJwt);
        String body = "{\"url\":\"" + OWN_URL + "/portal\",\"eventTypes\":[\"order.created\"]}";

        assertEquals(201, asPortal(post("/api/v1/portal/endpoints"), token, body).getResponse().getStatus());
        MvcResult overQuota = asPortal(post("/api/v1/portal/endpoints"), token, body);
        assertEquals(402, overQuota.getResponse().getStatus(), overQuota.getResponse().getContentAsString());
        assertEquals(FREE_MAX_ENDPOINTS_PER_PROJECT, endpointRepository.countByProjectIdAndDeletedAtIsNull(project));
    }

    // ── helpers ──

    private String consumerPath(UUID project, UUID consumer) {
        return "/api/v1/projects/" + project + "/consumers/" + consumer;
    }

    private String portalToken(UUID project, UUID consumer) throws Exception {
        return portalTokenAs(project, consumer, jwt);
    }

    private String portalTokenAs(UUID project, UUID consumer, String bearer) throws Exception {
        return json(ok(withJwt(post(consumerPath(project, consumer) + "/portal-sessions"), bearer, "{}")))
                .get("token").asText();
    }

    private UUID createConsumer(UUID project, String apiKey, String bearer, String externalId, String name)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/projects/" + project + "/consumers");
        String body = "{\"externalId\":\"" + externalId + "\",\"name\":\"" + name + "\"}";
        MvcResult result = apiKey != null ? withKey(request, body) : withJwt(request, bearer, body);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return id(result);
    }

    private UUID createEndpointFor(UUID project, UUID consumer, String url) throws Exception {
        String body = consumer == null
                ? "{\"url\":\"" + url + "\"}"
                : "{\"url\":\"" + url + "\",\"consumerId\":\"" + consumer + "\"}";
        UUID id = id(ok(withJwt(post("/api/v1/projects/" + project + "/endpoints"), jwt, body)));
        endpointsToRetire.add(id);
        return id;
    }

    private Delivery seedDelivery(UUID endpointId, DeliveryStatus status) {
        Event event = eventRepository.save(Event.builder()
                .organizationId(organizationId).projectId(projectA)
                .eventType("order.created").payload("{}").build());
        return deliveryRepository.saveAndFlush(Delivery.builder()
                .organizationId(organizationId).eventId(event.getId()).endpointId(endpointId)
                .status(status).build());
    }

    private void expectPortalNotFound(MockHttpServletRequestBuilder request, String token, String body)
            throws Exception {
        MvcResult result = asPortal(request, token, body);
        assertEquals(404, result.getResponse().getStatus(),
                result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
                        + " → " + result.getResponse().getContentAsString());
    }

    private MvcResult asPortal(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        return perform(request, body);
    }

    private MvcResult withKey(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("X-API-Key", keyA);
        return perform(request, body);
    }

    private MvcResult withJwt(MockHttpServletRequestBuilder request, String bearer, String body) throws Exception {
        request.header("Authorization", "Bearer " + bearer);
        return perform(request, body);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult ok(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        assertTrue(status >= 200 && status < 300,
                result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " → " + status + " "
                        + result.getResponse().getContentAsString());
        return result;
    }

    private String register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email(prefix + "-" + suffix + "@test.com")
                                .password("Test1234!")
                                .organizationName(prefix + " " + suffix)
                                .build())))
                .andReturn();
        assertEquals(201, registered.getResponse().getStatus(), registered.getResponse().getContentAsString());
        return json(registered).get("accessToken").asText();
    }

    private UUID createProject(String bearer, String name) throws Exception {
        return id(ok(withJwt(post("/api/v1/projects"), bearer,
                objectMapper.writeValueAsString(ProjectRequest.builder().name(name).build()))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID id(MvcResult result) throws Exception {
        return UUID.fromString(json(result).get("id").asText());
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
