package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.security.AllowedInDemo;
import com.webhook.platform.api.security.JwtUtil;
import com.webhook.platform.api.service.demo.DemoDataRemover;
import com.webhook.platform.api.service.demo.DemoDataSeeder;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.demo.DemoTenant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public demo, end to end: a visitor with no account opens a session, can read everything in
 * the demo organization, and can change nothing — by any handler, not only the ones a person
 * would think to try.
 *
 * <p>The walk over every state-changing handler is the point of this class. It asks the running
 * application which handlers exist rather than listing them, so a handler added next year is
 * covered the day it lands: it either refuses a demo session, or it carries
 * {@link AllowedInDemo}, whose set {@code DemoSessionAllowListTest} freezes.
 */
@TestPropertySource(properties = {
        "demo.enabled=true",
        "demo.session-ttl-minutes=30"
})
class DemoSessionIntegrationTest extends AbstractIntegrationTest {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::([^}]*))?}");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private DemoDataSeeder seeder;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void allowDemoSessions() {
        when(authRateLimiterService.allowDemoSession(anyString())).thenReturn(true);
    }

    private String openSession() throws Exception {
        // As the anonymous visitor it is: no tenant scope, which AbstractIntegrationTest otherwise
        // leaves on the thread MockMvc runs the request on.
        TenantContext.clear();
        MvcResult result;
        try {
            result = openSessionAnonymously();
        } finally {
            TenantContext.set(TenantContext.SYSTEM);
        }
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private MvcResult openSessionAnonymously() throws Exception {
        return mockMvc.perform(post("/api/v1/public/demo/session")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                // Nothing that outlives the token: no refresh cookie, no refresh token.
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();
    }

    // ── Opening a session ──────────────────────────────────────────

    @Test
    void aSessionIsAViewerInTheDemoOrganizationThatSaysItIsTheDemo() throws Exception {
        String token = openSession();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.organization.id").value(DemoTenant.ORGANIZATION_ID.toString()));
    }

    @Test
    void aSessionIsRefusedOverTheRateLimit() throws Exception {
        when(authRateLimiterService.allowDemoSession(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/public/demo/session").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void anExpiredSessionIsNoSessionAtAll() throws Exception {
        String expired = jwtUtil.generateDemoAccessToken(
                DemoTenant.USER_ID, DemoTenant.ORGANIZATION_ID, Duration.ofSeconds(-5));

        mockMvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionCannotBeRefreshedIntoALongerOne() throws Exception {
        String token = openSession();

        mockMvc.perform(post("/api/v1/auth/refresh").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_read_only"));
    }

    @Test
    void aDemoTokenOpensNothingOutsideTheApi() throws Exception {
        String token = openSession();

        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ── Reading ────────────────────────────────────────────────────

    @Test
    void theDemoCanBeReadInFull() throws Exception {
        String token = openSession();
        String project = DemoTenant.PROJECT_ID.toString();

        mockMvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Acme Shop"));
        mockMvc.perform(get("/api/v1/projects/" + project + "/endpoints").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4));
        mockMvc.perform(get("/api/v1/deliveries/projects/" + project).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isNotEmpty());
        mockMvc.perform(get("/api/v1/projects/" + project + "/dlq").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isNotEmpty());
        mockMvc.perform(get("/api/v1/projects/" + project + "/incoming-sources").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
        mockMvc.perform(get("/api/v1/dashboard/projects/" + project + "/analytics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.totalDeliveries").value(greaterThan(0)));
    }

    @Test
    void exportsAreRefusedAlthoughTheyAreReads() throws Exception {
        String token = openSession();

        mockMvc.perform(get("/api/v1/audit-log/export").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_read_only"));
        mockMvc.perform(get("/api/v1/orgs/" + DemoTenant.ORGANIZATION_ID + "/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_read_only"));
    }

    @Test
    void theDemoSeesNoOtherOrganization() throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email("demo-neighbour@example.com").password("Test1234!")
                                .organizationName("Neighbour").build())))
                .andExpect(status().isCreated())
                .andReturn();
        String owner = objectMapper.readValue(registered.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();
        MvcResult created = mockMvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Private\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String privateProject = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        String token = openSession();
        mockMvc.perform(get("/api/v1/projects/" + privateProject).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ── Changing anything ──────────────────────────────────────────

    @Test
    void everyStateChangingHandlerRefusesADemoSession() throws Exception {
        String token = openSession();
        List<String> leaks = new ArrayList<>();
        Set<String> walked = new TreeSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.webhook.platform.api.controller")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (method == RequestMethod.GET || method == RequestMethod.HEAD || method == RequestMethod.OPTIONS) {
                    continue;
                }
                for (String pattern : info.getPatternValues()) {
                    if (!pattern.startsWith("/api/")) {
                        continue;
                    }
                    String id = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
                    walked.add(id);
                    MediaType contentType = info.getConsumesCondition().getConsumableMediaTypes().stream()
                            .findFirst().orElse(MediaType.APPLICATION_JSON);
                    MvcResult result = mockMvc.perform(request(HttpMethod.valueOf(method.name()), concrete(pattern))
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(contentType)
                                    .content(contentType.includes(MediaType.APPLICATION_JSON) ? "{}" : ""))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    String body = result.getResponse().getContentAsString();
                    boolean refusedAsDemo = status == 403 && body.contains("\"demo_read_only\"");
                    boolean allowed = handler.hasMethodAnnotation(AllowedInDemo.class);

                    if (allowed) {
                        if (refusedAsDemo) {
                            leaks.add(id + " is @AllowedInDemo but was refused");
                        }
                    } else if (pattern.startsWith("/api/v1/admin/") || pattern.startsWith("/api/v1/portal/")) {
                        // Refused before the interceptor, by the filter chain: a demo session is
                        // neither the platform admin nor a portal session.
                        if (status != 403) {
                            leaks.add(method + " " + pattern + " (" + id + ") answered " + status);
                        }
                    } else if (!refusedAsDemo) {
                        leaks.add(method + " " + pattern + " (" + id + ") answered " + status + ": " + body);
                    }
                }
            }
        }

        assertThat(walked).as("the walk found too few handlers to mean anything").hasSizeGreaterThan(100);
        assertThat(leaks).as("state-changing handlers a demo session got past").isEmpty();
    }

    @Test
    void signingOutEndsTheDemoOnly() throws Exception {
        String token = openSession();

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)
                        .cookie(new Cookie("refresh_token", "someone-elses-real-session")))
                .andExpect(status().isNoContent())
                // The browser's own refresh cookie is not the demo's to clear.
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ── Side doors ─────────────────────────────────────────────────

    @Test
    void theDemoSourcesTakeNoWebhooks() throws Exception {
        String ingressToken = jdbc.queryForObject(
                "SELECT ingress_path_token FROM incoming_sources WHERE organization_id = ? AND slug = 'stripe'",
                String.class, DemoTenant.ORGANIZATION_ID);

        mockMvc.perform(post("/ingress/" + ingressToken).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nothingSeededIsWorkForTheWorker() {
        // What the worker picks up: PENDING or PROCESSING, or anything with a retry due, or an
        // outbox row. The demo must have none of it, or its reserved hosts would be attempted.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deliveries WHERE organization_id = ? "
                        + "AND (status IN ('PENDING', 'PROCESSING') OR next_retry_at IS NOT NULL)",
                Integer.class, DemoTenant.ORGANIZATION_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incoming_forward_attempts WHERE organization_id = ? "
                        + "AND (status IN ('PENDING', 'PROCESSING') OR next_retry_at IS NOT NULL)",
                Integer.class, DemoTenant.ORGANIZATION_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_messages WHERE project_id = ?",
                Integer.class, DemoTenant.PROJECT_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM endpoints WHERE organization_id = ? "
                        + "AND url NOT LIKE 'https://%.example/%'",
                Integer.class, DemoTenant.ORGANIZATION_ID)).isZero();
    }

    // ── Seeding ────────────────────────────────────────────────────

    @Test
    void withTheDemoOnNothingRemovesIt() {
        assertThat(applicationContext.getBeansOfType(DemoDataRemover.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?",
                Integer.class, DemoTenant.ORGANIZATION_ID)).isOne();
    }

    @Test
    void seedingAgainChangesTheSetupNotAtAllAndReplacesTheHistory() {
        String secretBefore = jdbc.queryForObject("SELECT secret_encrypted FROM endpoints WHERE organization_id = ? "
                + "ORDER BY id LIMIT 1", String.class, DemoTenant.ORGANIZATION_ID);
        int eventsBefore = count("events");

        seeder.seed();
        seeder.seed();

        assertThat(count("organizations", "id")).isEqualTo(1);
        assertThat(count("memberships")).isEqualTo(1);
        assertThat(count("projects")).isEqualTo(1);
        assertThat(count("endpoints")).isEqualTo(4);
        assertThat(count("subscriptions")).isEqualTo(11);
        assertThat(count("incoming_sources")).isEqualTo(2);
        assertThat(count("incoming_destinations")).isEqualTo(3);
        assertThat(count("consumers")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT secret_encrypted FROM endpoints WHERE organization_id = ? "
                + "ORDER BY id LIMIT 1", String.class, DemoTenant.ORGANIZATION_ID)).isEqualTo(secretBefore);
        // Replaced, not appended: the same order of magnitude, not twice or three times as much.
        assertThat(count("events")).isBetween((int) (eventsBefore * 0.8), (int) (eventsBefore * 1.2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deliveries WHERE organization_id = ? AND status = 'DLQ'",
                Integer.class, DemoTenant.ORGANIZATION_ID)).isPositive();
    }

    @Test
    void theDemoHasOneConsumerWhoseEndpointIsGroupedUnderIt() throws Exception {
        String token = openSession();
        String project = DemoTenant.PROJECT_ID.toString();

        MvcResult consumers = mockMvc.perform(get("/api/v1/projects/" + project + "/consumers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].endpointCount").value(1))
                .andReturn();
        String consumerId = objectMapper.readTree(consumers.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM endpoints WHERE organization_id = ? AND consumer_id = ?",
                Integer.class, DemoTenant.ORGANIZATION_ID, UUID.fromString(consumerId))).isOne();
    }

    @Test
    void anEndpointSeededBeforeTheConsumerExistedIsGroupedUnderItOnTheNextSeed() {
        // An installation seeded by an earlier release has the endpoints already, and inserting
        // them again changes nothing, so the grouping cannot ride on the insert.
        jdbc.update("UPDATE endpoints SET consumer_id = NULL WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);
        jdbc.update("DELETE FROM consumers WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);

        seeder.seed();

        assertThat(count("consumers")).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM endpoints WHERE organization_id = ? "
                + "AND consumer_id IS NOT NULL", Integer.class, DemoTenant.ORGANIZATION_ID)).isOne();
    }

    @Test
    void theDemoMemberIsPutBackToViewerIfAnythingChangedIt() {
        jdbc.update("UPDATE memberships SET role = 'OWNER' WHERE organization_id = ?", DemoTenant.ORGANIZATION_ID);

        seeder.seed();

        assertThat(jdbc.queryForObject("SELECT role FROM memberships WHERE organization_id = ?",
                String.class, DemoTenant.ORGANIZATION_ID)).isEqualTo("VIEWER");
    }

    @Test
    void thePlatformOverviewDoesNotCountTheDemo() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/overview")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode overview = objectMapper.readTree(result.getResponse().getContentAsString());

        int otherEvents = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE organization_id <> ? "
                + "AND created_at >= now() - interval '30 days'", Integer.class, DemoTenant.ORGANIZATION_ID);
        int otherOrganizations = jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE id <> ?",
                Integer.class, DemoTenant.ORGANIZATION_ID);
        assertThat(overview.get("events30d").asInt()).isEqualTo(otherEvents);
        assertThat(overview.get("organizations").asInt()).isEqualTo(otherOrganizations);
        assertThat(overview.get("deliveriesFailed24h").asInt()).isZero();
        for (JsonNode signup : overview.get("recentSignups")) {
            assertThat(signup.get("userId").asText()).isNotEqualTo(DemoTenant.USER_ID.toString());
        }
    }

    private int count(String table) {
        return count(table, "organization_id");
    }

    private int count(String table, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, DemoTenant.ORGANIZATION_ID);
    }

    /** A URL the pattern matches: the demo's own ids where the name says which, a fresh id elsewhere. */
    private static String concrete(String pattern) {
        Matcher m = PATH_VARIABLE.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String regex = m.group(2);
            String value;
            if (name.equals("projectId")) {
                value = DemoTenant.PROJECT_ID.toString();
            } else if (name.equals("orgId") || name.equals("organizationId")) {
                value = DemoTenant.ORGANIZATION_ID.toString();
            } else if (regex != null && regex.contains("\\d")) {
                value = "1";
            } else {
                value = UUID.randomUUID().toString();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
