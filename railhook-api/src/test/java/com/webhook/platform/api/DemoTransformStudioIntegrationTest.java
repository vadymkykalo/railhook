package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.service.demo.DemoDataSeeder;
import com.webhook.platform.api.service.demo.DemoDryRunMask;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.demo.DemoTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Transform Studio inside the public demo: a visitor with no account runs JavaScript on our
 * servers, sees what it produced, and gets nothing else.
 *
 * <p>Three things have to hold at once, and they pull against each other, which is why they are
 * in one class. The two handlers that execute a script answer a demo session — otherwise the
 * Studio is a screenshot. The dry-run's signature is not in the answer — otherwise a token
 * anybody can mint is a signing oracle. And everything around them still refuses: saving a
 * script, creating, editing or deleting a Transformation, and the rest of the API.
 *
 * <p>{@code DemoSessionIntegrationTest} walks every state-changing handler and
 * {@code DemoSessionAllowListTest} freezes the set that may be excepted; this one is about what
 * those two exceptions actually hand over.
 */
@TestPropertySource(properties = {
        "demo.enabled=true",
        "demo.session-ttl-minutes=30"
})
class DemoTransformStudioIntegrationTest extends AbstractIntegrationTest {

    /** Written against the demo's own {@code order.created} shape, as the seeded one is. */
    private static final String SCRIPT = """
            function handler(webhook) {
              var lines = webhook.payload.data.items.map(function (item) {
                return { sku: item.sku, total: item.quantity * Number(item.unit_price) };
              });
              return { payload: { order: webhook.payload.data.id, lines: lines },
                       headers: { 'X-Lines': String(lines.length) } };
            }
            """;

    private static final String ORDER = """
            {"type":"order.created","occurred_at":"2026-09-20T14:05:09.123Z","data":{
              "id":"ord_1042","status":"pending",
              "customer":{"id":"cus_9f1","email":"ada@customer.example"},
              "items":[{"sku":"WIDGET","name":"A widget","quantity":2,"unit_price":"19.99"}],
              "total":"39.98","currency":"USD"}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DemoDataSeeder seeder;

    @BeforeEach
    void allowDemoSessionsAndScriptRuns() {
        when(authRateLimiterService.allowDemoSession(anyString())).thenReturn(true);
        when(authRateLimiterService.allowDemoScriptRun(anyString(), any())).thenReturn(true);
        seeder.seed();
    }

    private String openSession() throws Exception {
        // As the anonymous visitor it is: no tenant scope on the thread MockMvc runs the request on.
        TenantContext.clear();
        MvcResult result;
        try {
            result = mockMvc.perform(post("/api/v1/public/demo/session")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andReturn();
        } finally {
            TenantContext.set(TenantContext.SYSTEM);
        }
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String studio() {
        return "/api/v1/projects/" + DemoTenant.PROJECT_ID + "/transform-preview";
    }

    private UUID anEndpoint() {
        return jdbc.queryForObject("SELECT id FROM endpoints WHERE organization_id = ? ORDER BY id LIMIT 1",
                UUID.class, DemoTenant.ORGANIZATION_ID);
    }

    // ── Running a script ───────────────────────────────────────────

    @Test
    void aVisitorCanRunAScriptAndSeeWhatItProduced() throws Exception {
        String token = openSession();

        MvcResult result = mockMvc.perform(post(studio()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "inputPayload", ORDER, "template", SCRIPT, "kind", "JAVASCRIPT",
                                "eventType", "order.created"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode output = objectMapper.readTree(body.get("outputPayload").asText());
        assertThat(output.get("order").asText()).isEqualTo("ord_1042");
        assertThat(output.get("lines").get(0).get("total").asDouble()).isEqualTo(39.98);
        assertThat(body.get("kind").asText()).isEqualTo("JAVASCRIPT");
    }

    @Test
    void aScriptThatFailsIsReportedRatherThanRefused() throws Exception {
        // The sandbox is the demo's protection, not the 403: a broken script has to come back as
        // a readable error, because reading the error is what the Studio is for.
        String token = openSession();

        mockMvc.perform(post(studio()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "inputPayload", ORDER, "kind", "JAVASCRIPT",
                                "template", "function handler(webhook) { return webhook.nope.nope; }"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    // ── The dry-run, and its signature ─────────────────────────────

    @Test
    void theDryRunAnswersTheDemoWithoutAUsableSignature() throws Exception {
        String token = openSession();
        UUID endpoint = anEndpoint();

        MvcResult result = mockMvc.perform(post(studio() + "/delivery-dry-run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "payload", ORDER, "payloadTemplate", SCRIPT, "kind", "JAVASCRIPT",
                                "endpointId", endpoint.toString(), "eventType", "order.created"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // Everything the Studio shows is there: the body, the URL, the headers.
                .andExpect(jsonPath("$.endpointUrl").isString())
                .andExpect(jsonPath("$.transformedPayload").isString())
                .andExpect(jsonPath("$.requestHeaders['X-Timestamp']").isString())
                // The one thing that is a capability is not.
                .andExpect(jsonPath("$.signature").value(DemoDryRunMask.MASKED))
                .andExpect(jsonPath("$.requestHeaders['X-Signature']").value(DemoDryRunMask.MASKED))
                .andReturn();

        // Nothing anywhere in the answer that a receiver would verify: not the signature, and not
        // the secret it would have been computed from.
        String body = result.getResponse().getContentAsString();
        String secret = jdbc.queryForObject("SELECT secret_encrypted FROM endpoints WHERE id = ?",
                String.class, endpoint);
        assertThat(body).doesNotContain("v1=").doesNotContain(secret);
    }

    @Test
    void aRealCallerStillGetsARealSignature() throws Exception {
        // The mask is the demo's, not the endpoint's: masking for everybody would quietly break
        // the one thing a dry-run is for.
        String owner = registerSomeoneWithAProject();
        UUID project = UUID.fromString(objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/projects").header("Authorization", "Bearer " + owner)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Studio\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText());
        String endpoint = objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/projects/" + project + "/endpoints")
                                .header("Authorization", "Bearer " + owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"url\":\"https://receiver.example/hook\",\"description\":\"Mine\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/api/v1/projects/" + project + "/transform-preview/delivery-dry-run")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "payload", ORDER, "endpointId", endpoint, "eventType", "order.created"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signature").isString())
                .andExpect(jsonPath("$.signature").value(not(DemoDryRunMask.MASKED)));
    }

    // ── What is still refused ──────────────────────────────────────

    @Test
    void runningAScriptIsNotSavingOne() throws Exception {
        String token = openSession();
        String transformations = "/api/v1/projects/" + DemoTenant.PROJECT_ID + "/transformations";
        UUID existing = jdbc.queryForObject("SELECT id FROM transformations WHERE organization_id = ? "
                + "ORDER BY id LIMIT 1", UUID.class, DemoTenant.ORGANIZATION_ID);
        String scriptBefore = jdbc.queryForObject("SELECT template FROM transformations WHERE id = ?",
                String.class, existing);
        int countBefore = count("transformations");

        mockMvc.perform(post(transformations).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mine\",\"template\":\"" + "function handler(w){return {payload:{}};}"
                                + "\",\"kind\":\"JAVASCRIPT\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_read_only"));
        mockMvc.perform(request(HttpMethod.PUT, transformations + "/" + existing)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\",\"template\":\"function handler(w){return {payload:{}};}\","
                                + "\"kind\":\"JAVASCRIPT\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_read_only"));
        mockMvc.perform(request(HttpMethod.DELETE, transformations + "/" + existing)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_read_only"));

        assertThat(count("transformations")).isEqualTo(countBefore);
        assertThat(jdbc.queryForObject("SELECT template FROM transformations WHERE id = ?", String.class, existing))
                .isEqualTo(scriptBefore);
    }

    @Test
    void theStudioIsNotAWayIntoTheRestOfTheApi() throws Exception {
        String token = openSession();

        // The neighbours of the two allowed handlers, all of which run a script's worth of nothing.
        mockMvc.perform(post("/api/v1/projects/" + DemoTenant.PROJECT_ID + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://elsewhere.example/hook\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_read_only"));
        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_read_only"));
    }

    // ── The budget ─────────────────────────────────────────────────

    @Test
    void aDemoSessionOutOfScriptRunsIsRefused() throws Exception {
        String token = openSession();
        when(authRateLimiterService.allowDemoScriptRun(anyString(), any())).thenReturn(false);

        mockMvc.perform(post(studio()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "inputPayload", ORDER, "template", SCRIPT, "kind", "JAVASCRIPT"))))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(post(studio() + "/delivery-dry-run").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "payload", ORDER, "payloadTemplate", SCRIPT, "kind", "JAVASCRIPT",
                                "endpointId", anEndpoint().toString()))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void theBudgetIsTheDemosAloneAndReadsAreNotOnIt() throws Exception {
        String token = openSession();
        when(authRateLimiterService.allowDemoScriptRun(anyString(), any())).thenReturn(false);

        // A signed-in caller's runs are attributable to an account; this budget is not theirs.
        String owner = registerSomeoneWithAProject();
        UUID project = UUID.fromString(objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/projects").header("Authorization", "Bearer " + owner)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Budget\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText());
        mockMvc.perform(post("/api/v1/projects/" + project + "/transform-preview")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "inputPayload", ORDER, "template", SCRIPT, "kind", "JAVASCRIPT"))))
                .andExpect(status().isOk());

        // And the demo can still look at everything while it is out of runs: the budget is on
        // starting a script, not on the demo.
        mockMvc.perform(get("/api/v1/projects/" + DemoTenant.PROJECT_ID + "/transformations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ── What the visitor opens on ──────────────────────────────────

    @Test
    void theDemoShipsAJavaScriptTransformationWiredToASubscription() throws Exception {
        String token = openSession();

        MvcResult listed = mockMvc.perform(get("/api/v1/projects/" + DemoTenant.PROJECT_ID + "/transformations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode transformations = objectMapper.readTree(listed.getResponse().getContentAsString());
        JsonNode script = null;
        for (JsonNode candidate : transformations) {
            if ("JAVASCRIPT".equals(candidate.get("kind").asText())) {
                script = candidate;
            }
        }
        assertThat(script).as("the demo has a JavaScript transformation to open the Studio on").isNotNull();
        // The three things the template language cannot do, which is why the example exists.
        assertThat(script.get("template").asText())
                .contains(".map(")
                .contains("if (")
                .contains("new Date(");

        UUID id = UUID.fromString(script.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscriptions WHERE transformation_id = ?",
                Integer.class, id))
                .as("wired to a Subscription, so the Studio opens on real code against real events")
                .isPositive();

        // And it runs, here, against the demo's own most recent event of that type.
        String recent = jdbc.queryForObject("SELECT payload::text FROM events WHERE organization_id = ? "
                        + "AND event_type = 'order.created' ORDER BY created_at DESC LIMIT 1",
                String.class, DemoTenant.ORGANIZATION_ID);
        mockMvc.perform(post(studio()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "inputPayload", recent, "transformationId", id.toString(),
                                "eventType", "order.created"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.kind").value("JAVASCRIPT"))
                .andExpect(jsonPath("$.outputPayload").isString());
    }

    @Test
    void seedingTwiceLeavesOneOfIt() {
        int before = count("transformations");

        seeder.seed();

        assertThat(count("transformations")).isEqualTo(before);
    }

    private String registerSomeoneWithAProject() throws Exception {
        String email = "studio-" + UUID.randomUUID() + "@example.com";
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Test1234!\","
                                + "\"organizationName\":\"Studio user\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(registered.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
