package com.webhook.platform.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-key project scoping was enforced by an opt-in, per-handler call
 * ({@code AuthContext.validateProjectAccess}) that roughly a third of
 * {@code {projectId}} routes never made. Because {@code AuthContext.organizationId}
 * is derived from an API key's own project, the service-layer check that only
 * compares organization IDs passed for <b>any</b> project in the same org — so a
 * key scoped to one project could reach another project's resources in routes
 * that forgot the call. Worst case: {@code POST
 * /api/v1/projects/{projectId}/endpoints/{id}/rotate-secret} never called it,
 * so a key scoped to "staging" could rotate a "production" endpoint's signing
 * secret and receive the new secret back in plaintext.
 *
 * <p>The fix moves the check into {@link ScopeEnforcementInterceptor}: it now runs
 * for every request, reads the resolved {@code projectId} path variable straight
 * off the servlet request (not off whatever the handler method happens to bind),
 * and confines API-key auth to that project unless the handler carries the
 * explicit {@link ProjectScopeExempt} opt-out.
 *
 * <p>This class has two halves:
 * <ul>
 *   <li>{@link #everyProjectIdRouteIsCoveredOrExplicitlyExempt()} — the
 *       structural guarantee. It scans every {@code @RestController} in the
 *       {@code controller} package by reflection, resolves each handler's full
 *       path (class-level {@code @RequestMapping} + method-level mapping,
 *       combined — {@code DeliveryController} puts {@code {projectId}} only in
 *       a method-level mapping, so checking the class annotation alone is not
 *       enough), and asserts every route containing {@code {projectId}} is
 *       either unexempt (default: enforced) or carries a reasoned
 *       {@link ProjectScopeExempt}. This is what stops the regression coming
 *       back — a new controller with a forgotten check fails this test the
 *       moment it's added, with no HTTP call required.</li>
 *   <li>The remaining {@code @Test} methods are behavioural, end-to-end
 *       reproductions against the previously-uncovered controllers (Schema,
 *       IncomingDestination), a re-verification of TestEndpointController
 *       (already fixed directly by TestEndpointController's own per-handler calls; this asserts the
 *       interceptor now also covers it), and the rotate-secret worst case.</li>
 * </ul>
 */
@AutoConfigureMockMvc
public class ProjectScopeEnforcementIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String jwt;
    private UUID projectAId; // owns the API key below
    private UUID projectBId; // a different project in the SAME org
    private String apiKeyForProjectA;

    // Endpoint creation runs real webhook-URL validation (SSRF guard), which
    // does a live DNS lookup unless the host is allow-listed. Allow-list the
    // fixed hostnames used below instead of depending on outbound DNS/network
    // access being available wherever this test runs.
    @DynamicPropertySource
    static void urlValidationProperties(DynamicPropertyRegistry registry) {
        registry.add("webhook.url-validation.allowed-hosts", () -> "prod.example.com,staging.example.com");
    }

    @BeforeEach
    void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jwt = registerAndGetJwt("cross-project-" + suffix + "@test.com", "Cross-Project Test Org " + suffix);

        projectAId = createProject(jwt, "Project A " + suffix);
        projectBId = createProject(jwt, "Project B " + suffix);

        MvcResult apiKeyResult = mockMvc.perform(post("/api/v1/projects/" + projectAId + "/api-keys")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder()
                                .name("cross-project-key")
                                .scope(ApiKeyScope.READ_WRITE)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        apiKeyForProjectA = objectMapper.readTree(apiKeyResult.getResponse().getContentAsString())
                .get("key").asText();
    }

    private String registerAndGetJwt(String email, String orgName) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName(orgName)
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID createProject(String jwt, String name) throws Exception {
        ProjectRequest request = ProjectRequest.builder()
                .name(name)
                .description("Test project")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Structural test — the actual point of this class.
    // ─────────────────────────────────────────────────────────────────────

    private record RouteHandler(Class<?> controller, Method method, String fullPath) {
        String label() {
            return controller.getSimpleName() + "." + method.getName();
        }
    }

    /**
     * Every {@code {projectId}} route, found by classpath scan, must be either
     * unexempt (the interceptor enforces it automatically) or carry an explicit,
     * reasoned {@link ProjectScopeExempt}. Also sanity-checks that the scan
     * itself still finds a realistic number of routes and that every one of
     * them lives under the path pattern ({@code /api/**}) that
     * {@code WebConfig} actually registers the interceptor against — a route
     * added outside that pattern would silently bypass the guard no matter
     * what the annotation says.
     */
    @Test
    public void everyProjectIdRouteIsCoveredOrExplicitlyExempt() {
        List<RouteHandler> projectScopedRoutes = scanControllerPackageForProjectIdRoutes();

        assertTrue(projectScopedRoutes.size() >= 60,
                "Expected at least 60 {projectId} routes across the controller package "
                        + "(found " + projectScopedRoutes.size() + ") — the classpath scan itself "
                        + "may be broken (wrong package, filter, or annotation resolution).");

        List<String> exemptWithoutReason = new ArrayList<>();
        List<String> outsideInterceptorPattern = new ArrayList<>();

        for (RouteHandler route : projectScopedRoutes) {
            boolean methodExempt = AnnotatedElementUtils.hasAnnotation(route.method(), ProjectScopeExempt.class);
            boolean classExempt = AnnotatedElementUtils.hasAnnotation(route.controller(), ProjectScopeExempt.class);

            if (methodExempt || classExempt) {
                String reason = methodExempt
                        ? AnnotatedElementUtils.findMergedAnnotation(route.method(), ProjectScopeExempt.class).reason()
                        : AnnotatedElementUtils.findMergedAnnotation(route.controller(), ProjectScopeExempt.class).reason();
                if (reason == null || reason.isBlank()) {
                    exemptWithoutReason.add(route.label());
                }
                // Exempt with a reason: deliberate, reviewable opt-out. Covered.
            }
            // Not exempt: covered by default, unconditional enforcement in
            // ScopeEnforcementInterceptor. Nothing further to assert per-route —
            // that behaviour is what the tests below exercise end-to-end.

            // WebConfig registers the interceptor on "/api/**"; every discovered
            // {projectId} route must live under that prefix or the annotation
            // (or its absence) is meaningless — the interceptor never runs.
            if (!route.fullPath().startsWith("/api/")) {
                outsideInterceptorPattern.add(route.label() + " (" + route.fullPath() + ")");
            }
        }

        assertTrue(exemptWithoutReason.isEmpty(),
                "@ProjectScopeExempt without a reason() on: " + exemptWithoutReason);
        assertTrue(outsideInterceptorPattern.isEmpty(),
                "{projectId} route(s) outside the interceptor's registered \"/api/**\" pattern "
                        + "(WebConfig) — the automatic guard cannot reach these: " + outsideInterceptorPattern);

        // Pin down that specific, previously-vulnerable handlers are actually
        // found by the scan and are not exempt — this ties the structural
        // guarantee back to the concrete defects it protects against.
        Set<String> mustBeCoveredAndUnexempt = Set.of(
                "EndpointController.rotateSecret",
                "SchemaController.listEventTypes",
                "SchemaController.createEventType",
                "IncomingDestinationController.listDestinations",
                "IncomingDestinationController.createDestination",
                "TestEndpointController.list"
        );
        Set<String> foundLabels = projectScopedRoutes.stream().map(RouteHandler::label)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> foundNonExemptLabels = new java.util.HashSet<>(foundLabels);
        for (RouteHandler route : projectScopedRoutes) {
            boolean exempt = AnnotatedElementUtils.hasAnnotation(route.method(), ProjectScopeExempt.class)
                    || AnnotatedElementUtils.hasAnnotation(route.controller(), ProjectScopeExempt.class);
            if (exempt) {
                foundNonExemptLabels.remove(route.label());
            }
        }
        for (String required : mustBeCoveredAndUnexempt) {
            assertTrue(foundLabels.contains(required),
                    "Expected the classpath scan to find " + required
                            + " as a {projectId} route (method may have been renamed/removed)");
            assertTrue(foundNonExemptLabels.contains(required),
                    required + " must not be @ProjectScopeExempt");
        }
    }

    private static final List<Class<? extends java.lang.annotation.Annotation>> MAPPING_ANNOTATIONS = List.of(
            GetMapping.class, PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class,
            RequestMapping.class
    );

    private List<RouteHandler> scanControllerPackageForProjectIdRoutes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<RouteHandler> found = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.webhook.platform.api.controller")) {
            Class<?> controllerClass;
            try {
                controllerClass = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }

            String[] classPaths = mergedPaths(AnnotatedElementUtils.findMergedAnnotation(controllerClass, RequestMapping.class));

            for (Method method : controllerClass.getDeclaredMethods()) {
                boolean isHandler = MAPPING_ANNOTATIONS.stream()
                        .anyMatch(a -> AnnotatedElementUtils.hasAnnotation(method, a));
                if (!isHandler) {
                    continue;
                }

                String[] methodPaths = mergedPaths(AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));

                boolean hasProjectId = false;
                String matchedFullPath = null;
                for (String classPath : classPaths) {
                    for (String methodPath : methodPaths) {
                        String fullPath = combine(classPath, methodPath);
                        if (fullPath.contains("{projectId}")) {
                            hasProjectId = true;
                            matchedFullPath = fullPath;
                        }
                    }
                }

                if (hasProjectId) {
                    found.add(new RouteHandler(controllerClass, method, matchedFullPath));
                }
            }
        }
        return found;
    }

    private String[] mergedPaths(RequestMapping mapping) {
        if (mapping == null) {
            return new String[]{""};
        }
        String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return values.length > 0 ? values : new String[]{""};
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.startsWith("/") ? raw.substring(1) : raw;
        return (s.endsWith("/") && s.length() > 1) ? s.substring(0, s.length() - 1) : s;
    }

    private static String combine(String classPath, String methodPath) {
        String c = normalize(classPath);
        String m = normalize(methodPath);
        if (c.isEmpty() && m.isEmpty()) {
            return "/";
        }
        if (m.isEmpty()) {
            return "/" + c;
        }
        if (c.isEmpty()) {
            return "/" + m;
        }
        return "/" + c + "/" + m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Behavioural — previously-zero controllers (Schema, IncomingDestination),
    // TestEndpoint re-verification, and the rotate-secret worst case.
    // ─────────────────────────────────────────────────────────────────────

    // ── Schema (was 0/12) ──

    @Test
    public void apiKey_schema_listEventTypes_crossProject_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectBId + "/schemas")
                        .header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_schema_createEventType_crossProject_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectBId + "/schemas")
                        .header("X-API-Key", apiKeyForProjectA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                EventTypeCatalogRequest.builder().name("order.created").build())))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_schema_listEventTypes_ownProject_ok() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/schemas")
                        .header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isOk());
    }

    // ── IncomingDestination (was 0/5) ──

    @Test
    public void apiKey_incomingDestination_list_crossProject_forbidden() throws Exception {
        UUID sourceBId = createIncomingSource(projectBId);
        mockMvc.perform(get("/api/v1/projects/" + projectBId + "/incoming-sources/" + sourceBId + "/destinations")
                        .header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_incomingDestination_create_crossProject_forbidden() throws Exception {
        UUID sourceBId = createIncomingSource(projectBId);
        mockMvc.perform(post("/api/v1/projects/" + projectBId + "/incoming-sources/" + sourceBId + "/destinations")
                        .header("X-API-Key", apiKeyForProjectA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                IncomingDestinationRequest.builder().url("https://example.com/hook").build())))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_incomingDestination_list_ownProject_ok() throws Exception {
        UUID sourceAId = createIncomingSource(projectAId);
        mockMvc.perform(get("/api/v1/projects/" + projectAId + "/incoming-sources/" + sourceAId + "/destinations")
                        .header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isOk());
    }

    private UUID createIncomingSource(UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/incoming-sources")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                IncomingSourceRequest.builder().name("src-" + UUID.randomUUID()).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // ── TestEndpoint (was 0/6, fixed directly by its own per-handler calls; re-verify the
    //    interceptor also covers it now, redundantly with those per-handler
    //    calls — full coverage lives in TestEndpointIsolationTest) ──

    @Test
    public void apiKey_testEndpoint_list_crossProject_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + projectBId + "/test-endpoints")
                        .header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isForbidden());
    }

    // ── Rotate-secret: the worst concrete case from the task ──
    // A key scoped to "project A" (stand-in for staging) rotates the signing
    // secret of an endpoint that belongs to "project B" (stand-in for
    // production) in the SAME org. Unfixed: EndpointController.rotateSecret
    // never called validateProjectAccess, and EndpointService.rotateSecret
    // only checks organizationId — so this succeeded, rotated prod's secret,
    // and handed it back in plaintext to a key that should never have seen it.

    @Test
    public void apiKey_rotateSecret_crossProjectSameOrg_forbidden() throws Exception {
        UUID endpointBId = createEndpoint(projectBId, "https://prod.example.com/webhook");

        mockMvc.perform(post("/api/v1/projects/" + projectBId + "/endpoints/" + endpointBId + "/rotate-secret")
                        .header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isForbidden());
    }

    @Test
    public void apiKey_rotateSecret_ownProject_ok_andReturnsSecret() throws Exception {
        UUID endpointAId = createEndpoint(projectAId, "https://staging.example.com/webhook");

        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectAId + "/endpoints/" + endpointAId + "/rotate-secret")
                        .header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(endpointAId.toString(), json.get("id").asText());
        assertFalse(json.get("secret").asText().isBlank(),
                "Rotating your OWN endpoint's secret should still return it — whether the plaintext "
                        + "response itself is sound is a separate discussion from project scoping.");
    }

    private UUID createEndpoint(UUID projectId, String url) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/endpoints")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(EndpointRequest.builder().url(url).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
