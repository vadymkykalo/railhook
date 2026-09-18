package com.webhook.platform.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.AuditLog;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OAuthGrantRepository;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RateLimitInfo;
import com.webhook.platform.api.dto.RateLimitResult;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Connecting an AI app to {@code /mcp} the way claude.ai and ChatGPT do it: discover the
 * authorization server from the 401, register as a client, send a person through the consent
 * screen, trade the code for tokens with PKCE, and call tools with the access token.
 *
 * <p>What matters beyond the happy path is that a grant is exactly an API key in disguise — one
 * project, one scope — and that every way a grant should stop working actually stops it: a
 * READ_ONLY grant cannot write, a rotated refresh token cannot be replayed, a revoked grant and a
 * suspended approver authenticate nothing.
 */
class McpOAuthIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "https://railhook.test";
    private static final String REDIRECT = "https://claude.ai/api/mcp/auth_callback";
    private static final AtomicInteger RPC_ID = new AtomicInteger();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private OAuthGrantRepository grantRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private String jwt;
    private UUID userId;
    private UUID organizationId;
    private UUID projectA;
    private UUID projectB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.base-url", () -> BASE);
        registry.add("webhook.url-validation.allowed-hosts", () -> "oauth-a.example.com,oauth-b.example.com");
    }

    @BeforeEach
    void setup() throws Exception {
        when(redisRateLimiterService.tryAcquireWithInfo(any(UUID.class), anyInt())).thenReturn(
                RateLimitResult.builder().acquired(true).info(new RateLimitInfo(100, 99, 0L)).build());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode registered = register("oauth-" + suffix + "@test.com", "OAuth Org " + suffix);
        jwt = registered.get("accessToken").asText();
        JsonNode me = getJson("/api/v1/auth/me", jwt);
        userId = UUID.fromString(me.get("user").get("id").asText());
        organizationId = UUID.fromString(me.get("organization").get("id").asText());
        projectA = createProject("OAuth A " + suffix);
        projectB = createProject("OAuth B " + suffix);
    }

    // ── discovery ────────────────────────────────────────────────────────

    @Test
    void answersAnUnauthenticatedMcpCallWithTheResourceMetadataUrl() throws Exception {
        MvcResult result = mockMvc.perform(mcpPost(rpcBody("tools/list", Map.of())))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer resource_metadata=\"" + BASE + "/.well-known/oauth-protected-resource/mcp\"");

        MvcResult badToken = mockMvc.perform(mcpPost(rpcBody("tools/list", Map.of()))
                        .header("Authorization", "Bearer rhat_not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(badToken.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer error=\"invalid_token\"")
                .contains("resource_metadata=\"" + BASE + "/.well-known/oauth-protected-resource/mcp\"");
    }

    @Test
    void publishesProtectedResourceAndAuthorizationServerMetadata() throws Exception {
        JsonNode resource = getJson("/.well-known/oauth-protected-resource/mcp");
        assertThat(resource.get("resource").asText()).isEqualTo(BASE + "/mcp");
        assertThat(resource.get("authorization_servers").get(0).asText()).isEqualTo(BASE);
        assertThat(resource.get("bearer_methods_supported").get(0).asText()).isEqualTo("header");

        // At the root, RFC 9728 makes the resource the origin the document was fetched from.
        assertThat(getJson("/.well-known/oauth-protected-resource").get("resource").asText()).isEqualTo(BASE);

        JsonNode server = getJson("/.well-known/oauth-authorization-server");
        assertThat(server.get("issuer").asText()).isEqualTo(BASE);
        assertThat(server.get("authorization_endpoint").asText()).isEqualTo(BASE + "/oauth/authorize");
        assertThat(server.get("token_endpoint").asText()).isEqualTo(BASE + "/oauth/token");
        assertThat(server.get("registration_endpoint").asText()).isEqualTo(BASE + "/oauth/register");
        assertThat(server.get("revocation_endpoint").asText()).isEqualTo(BASE + "/oauth/revoke");
        assertThat(texts(server.get("code_challenge_methods_supported"))).containsExactly("S256");
        assertThat(texts(server.get("grant_types_supported"))).containsExactlyInAnyOrder("authorization_code", "refresh_token");
        assertThat(texts(server.get("response_types_supported"))).containsExactly("code");
        assertThat(texts(server.get("scopes_supported"))).containsExactlyInAnyOrder("mcp:read", "mcp:write");
        assertThat(server.get("authorization_response_iss_parameter_supported").asBoolean()).isTrue();
    }

    // ── registration ─────────────────────────────────────────────────────

    @Test
    void registersPublicClientsAndRefusesRedirectsThatCouldLeakACode() throws Exception {
        JsonNode client = registerClient(List.of(REDIRECT, "http://127.0.0.1:33418/callback"), "none");
        assertThat(client.get("client_id").asText()).isNotBlank();
        assertThat(client.has("client_secret")).isFalse();
        assertThat(client.get("token_endpoint_auth_method").asText()).isEqualTo("none");
        assertThat(client.get("client_name").asText()).isEqualTo("Claude");

        for (String bad : List.of("http://evil.example.com/cb", "https://claude.ai/cb#frag", "javascript:alert(1)",
                "not a uri", "file:///etc/passwd")) {
            mockMvc.perform(post("/oauth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "client_name", "Bad", "redirect_uris", List.of(bad),
                                    "token_endpoint_auth_method", "none"))))
                    .andExpect(status().isBadRequest());
        }

        JsonNode confidential = registerClient(List.of(REDIRECT), "client_secret_post");
        assertThat(confidential.get("client_secret").asText()).isNotBlank();
    }

    // ── the whole connection ─────────────────────────────────────────────

    @Test
    void connectsAnAppEndToEndAndEveryWayOfEndingItWorks() throws Exception {
        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        String verifier = verifier();

        String requestId = authorize(clientId, REDIRECT, challenge(verifier), "xyz-state", "mcp:read mcp:write");

        JsonNode consent = getJson("/api/v1/oauth/requests/" + requestId, jwt);
        assertThat(consent.get("clientName").asText()).isEqualTo("Claude");
        assertThat(consent.get("redirectHost").asText()).isEqualTo("claude.ai");
        assertThat(consent.get("requestedScope").asText()).isEqualTo("READ_WRITE");
        assertThat(consent.get("canGrantWrite").asBoolean()).isTrue();

        UriComponents redirect = approve(requestId, projectA, "READ_WRITE");
        assertThat(redirect.getHost()).isEqualTo("claude.ai");
        assertThat(redirect.getQueryParams().getFirst("state")).isEqualTo("xyz-state");
        assertThat(decode(redirect.getQueryParams().getFirst("iss"))).isEqualTo(BASE);
        String code = redirect.getQueryParams().getFirst("code");

        // The request was answered; it cannot be answered a second time.
        mockMvc.perform(post("/api/v1/oauth/requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectA, "scope", "READ_ONLY"))))
                .andExpect(status().isNotFound());

        // PKCE: the code is worthless without the verifier that made the challenge.
        JsonNode wrongVerifier = tokenError(form("grant_type", "authorization_code", "code", code,
                "redirect_uri", REDIRECT, "client_id", clientId, "code_verifier", verifier()));
        assertThat(wrongVerifier.get("error").asText()).isEqualTo("invalid_grant");

        JsonNode tokens = token(form("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", verifier, "resource", BASE + "/mcp"));
        assertThat(tokens.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expires_in").asInt()).isEqualTo(3600);
        assertThat(tokens.get("scope").asText()).isEqualTo("mcp:read mcp:write");
        String access = tokens.get("access_token").asText();
        String refresh = tokens.get("refresh_token").asText();

        // A code is single use.
        assertThat(tokenError(form("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", verifier)).get("error").asText()).isEqualTo("invalid_grant");
        // ...and replaying it revokes what it was exchanged for, as OAuth 2.1 asks.
        rpcStatus(access, "tools/list", 401);

        // Start over: a fresh approval, this time exchanged once.
        String verifier2 = verifier();
        String requestId2 = authorize(clientId, REDIRECT, challenge(verifier2), "s2", "mcp:read mcp:write");
        String code2 = approve(requestId2, projectA, "READ_WRITE").getQueryParams().getFirst("code");
        tokens = token(form("grant_type", "authorization_code", "code", code2, "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", verifier2));
        access = tokens.get("access_token").asText();
        refresh = tokens.get("refresh_token").asText();

        JsonNode listed = rpc(access, "tools/list", Map.of()).get("result");
        List<String> tools = new ArrayList<>();
        listed.get("tools").forEach(tool -> tools.add(tool.get("name").asText()));
        assertThat(tools).contains("list_endpoints", "create_endpoint");

        JsonNode created = callTool(access, "create_endpoint", Map.of("url", "https://oauth-a.example.com/hook"));
        assertThat(created.path("isError").asBoolean(false)).as(text(created)).isFalse();
        assertThat(objectMapper.readTree(text(created)).get("projectId").asText()).isEqualTo(projectA.toString());

        // The connection is listed on the project it was granted for, and on no other.
        JsonNode grants = getJson("/api/v1/projects/" + projectA + "/mcp-grants", jwt);
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).get("clientName").asText()).isEqualTo("Claude");
        assertThat(grants.get(0).get("scope").asText()).isEqualTo("READ_WRITE");
        assertThat(grants.get(0).get("lastUsedAt").isNull()).isFalse();
        assertThat(getJson("/api/v1/projects/" + projectB + "/mcp-grants", jwt)).isEmpty();
        String grantId = grants.get(0).get("id").asText();

        // Refresh rotates: the new pair works, the old refresh token is refused, and presenting it
        // again is taken as theft — the whole grant goes.
        JsonNode refreshed = token(form("grant_type", "refresh_token", "refresh_token", refresh, "client_id", clientId));
        String access2 = refreshed.get("access_token").asText();
        String refresh2 = refreshed.get("refresh_token").asText();
        assertThat(refresh2).isNotEqualTo(refresh);
        assertThat(access2).isNotEqualTo(access);
        rpcStatus(access, "tools/list", 401);
        assertThat(rpc(access2, "tools/list", Map.of()).has("result")).isTrue();

        // Another client cannot use this client's refresh token.
        String otherClient = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        assertThat(tokenError(form("grant_type", "refresh_token", "refresh_token", refresh2, "client_id", otherClient))
                .get("error").asText()).isEqualTo("invalid_grant");

        // Revoked from project settings: the token stops working at once.
        mockMvc.perform(delete("/api/v1/projects/" + projectA + "/mcp-grants/" + grantId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());
        rpcStatus(access2, "tools/list", 401);
        assertThat(tokenError(form("grant_type", "refresh_token", "refresh_token", refresh2, "client_id", clientId))
                .get("error").asText()).isEqualTo("invalid_grant");
        assertThat(getJson("/api/v1/projects/" + projectA + "/mcp-grants", jwt)).isEmpty();

        // Connecting and disconnecting are in the organization's audit log, under the person.
        List<AuditLog> audit = awaitAudit(grantId, 2);
        assertThat(audit).extracting(AuditLog::getAction).contains("CREATE", "REVOKE");
        assertThat(audit).allSatisfy(row -> {
            assertThat(row.getResourceType()).isEqualTo("McpGrant");
            assertThat(row.getOrganizationId()).isEqualTo(organizationId);
            assertThat(row.getUserId()).isEqualTo(userId);
        });
    }

    /** Audit rows are written on their own thread; wait for the ones naming this grant. */
    private List<AuditLog> awaitAudit(String grantId, int atLeast) throws InterruptedException {
        List<AuditLog> rows = List.of();
        for (int i = 0; i < 50 && rows.size() < atLeast; i++) {
            rows = auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId, PageRequest.of(0, 50))
                    .stream()
                    .filter(row -> row.getDetails() != null && row.getDetails().contains(grantId))
                    .toList();
            if (rows.size() < atLeast) {
                Thread.sleep(100);
            }
        }
        return rows;
    }

    @Test
    void reusingARotatedRefreshTokenRevokesTheGrant() throws Exception {
        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        JsonNode tokens = connect(clientId, projectA, "READ_ONLY");
        String oldRefresh = tokens.get("refresh_token").asText();

        JsonNode rotated = token(form("grant_type", "refresh_token", "refresh_token", oldRefresh, "client_id", clientId));
        String newAccess = rotated.get("access_token").asText();

        assertThat(tokenError(form("grant_type", "refresh_token", "refresh_token", oldRefresh, "client_id", clientId))
                .get("error").asText()).isEqualTo("invalid_grant");
        rpcStatus(newAccess, "tools/list", 401);
        assertThat(tokenError(form("grant_type", "refresh_token", "refresh_token",
                rotated.get("refresh_token").asText(), "client_id", clientId)).get("error").asText())
                .isEqualTo("invalid_grant");
    }

    @Test
    void aReadOnlyGrantReadsButCannotWrite() throws Exception {
        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        String access = connect(clientId, projectA, "READ_ONLY").get("access_token").asText();

        JsonNode listed = callTool(access, "list_endpoints", Map.of());
        assertThat(listed.path("isError").asBoolean(false)).as(text(listed)).isFalse();

        JsonNode write = callTool(access, "create_endpoint", Map.of("url", "https://oauth-a.example.com/ro"));
        assertThat(write.path("isError").asBoolean(false)).isTrue();
        assertThat(text(write)).contains("read-only");
    }

    @Test
    void aGrantSeesOnlyItsOwnProject() throws Exception {
        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        String accessB = connect(clientId, projectB, "READ_WRITE").get("access_token").asText();
        assertThat(callTool(accessB, "create_endpoint", Map.of("url", "https://oauth-b.example.com/b"))
                .path("isError").asBoolean(false)).isFalse();

        String accessA = connect(clientId, projectA, "READ_WRITE").get("access_token").asText();
        JsonNode page = objectMapper.readTree(text(callTool(accessA, "list_endpoints", Map.of())));
        assertThat(page.get("totalElements").asInt()).isZero();

        // A project of another organization cannot be picked on the consent screen at all.
        String otherJwt = register("oauth-other-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com",
                "Other Org").get("accessToken").asText();
        String verifier = verifier();
        String requestId = authorize(clientId, REDIRECT, challenge(verifier), "s", "mcp:read");
        mockMvc.perform(post("/api/v1/oauth/requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + otherJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectA, "scope", "READ_ONLY"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aGrantStopsWorkingWhenTheApproverIsSuspended() throws Exception {
        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        String access = connect(clientId, projectA, "READ_ONLY").get("access_token").asText();
        assertThat(rpc(access, "tools/list", Map.of()).has("result")).isTrue();

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId).orElseThrow();
        membership.setStatus(MembershipStatus.DISABLED);
        membershipRepository.save(membership);

        rpcStatus(access, "tools/list", 401);
    }

    @Test
    void aViewerMayGrantReadButNotWrite() throws Exception {
        Membership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId).orElseThrow();
        membership.setRole(MembershipRole.VIEWER);
        membershipRepository.save(membership);
        String viewerJwt = login();

        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        String requestId = authorize(clientId, REDIRECT, challenge(verifier()), "s", "mcp:read mcp:write");
        assertThat(getJson("/api/v1/oauth/requests/" + requestId, viewerJwt).get("canGrantWrite").asBoolean()).isFalse();

        mockMvc.perform(post("/api/v1/oauth/requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectA, "scope", "READ_WRITE"))))
                .andExpect(status().isForbidden());

        String verifier = verifier();
        String readRequest = authorize(clientId, REDIRECT, challenge(verifier), "s", "mcp:read");
        MvcResult approved = mockMvc.perform(post("/api/v1/oauth/requests/" + readRequest + "/approve")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectA, "scope", "READ_ONLY"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(approved.getResponse().getContentAsString()).get("redirectUrl").asText())
                .contains("code=");
    }

    @Test
    void denyingSendsTheAppAccessDenied() throws Exception {
        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        String requestId = authorize(clientId, REDIRECT, challenge(verifier()), "st", "mcp:read");
        MvcResult denied = mockMvc.perform(post("/api/v1/oauth/requests/" + requestId + "/deny")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        UriComponents redirect = UriComponentsBuilder.fromUriString(
                objectMapper.readTree(denied.getResponse().getContentAsString()).get("redirectUrl").asText()).build();
        assertThat(redirect.getQueryParams().getFirst("error")).isEqualTo("access_denied");
        assertThat(redirect.getQueryParams().getFirst("state")).isEqualTo("st");
        assertThat(redirect.getQueryParams().getFirst("code")).isNull();
    }

    @Test
    void refusesAnAuthorizationRequestThatBreaksTheRules() throws Exception {
        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();

        // An unregistered redirect is never redirected to: the browser goes to the consent page's
        // error state instead.
        MvcResult wrongRedirect = mockMvc.perform(get("/oauth/authorize")
                        .param("response_type", "code").param("client_id", clientId)
                        .param("redirect_uri", "https://evil.example.com/cb")
                        .param("code_challenge", challenge(verifier())).param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(wrongRedirect.getResponse().getHeader("Location"))
                .startsWith(BASE + "/oauth/consent?error=invalid_request");

        // A registered redirect without PKCE gets the error at the app.
        MvcResult noPkce = mockMvc.perform(get("/oauth/authorize")
                        .param("response_type", "code").param("client_id", clientId)
                        .param("redirect_uri", REDIRECT).param("state", "q"))
                .andExpect(status().isFound())
                .andReturn();
        UriComponents location = UriComponentsBuilder.fromUriString(noPkce.getResponse().getHeader("Location")).build();
        assertThat(location.getHost()).isEqualTo("claude.ai");
        assertThat(location.getQueryParams().getFirst("error")).isEqualTo("invalid_request");
        assertThat(location.getQueryParams().getFirst("state")).isEqualTo("q");

        MvcResult plain = mockMvc.perform(get("/oauth/authorize")
                        .param("response_type", "code").param("client_id", clientId).param("redirect_uri", REDIRECT)
                        .param("code_challenge", "abc").param("code_challenge_method", "plain"))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(plain.getResponse().getHeader("Location")).contains("error=invalid_request");

        MvcResult foreignResource = mockMvc.perform(get("/oauth/authorize")
                        .param("response_type", "code").param("client_id", clientId).param("redirect_uri", REDIRECT)
                        .param("code_challenge", challenge(verifier())).param("code_challenge_method", "S256")
                        .param("resource", "https://other.example.com/mcp"))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(foreignResource.getResponse().getHeader("Location")).contains("error=invalid_target");
    }

    @Test
    void aConfidentialClientMustPresentItsSecret() throws Exception {
        JsonNode client = registerClient(List.of(REDIRECT), "client_secret_post");
        String clientId = client.get("client_id").asText();
        String secret = client.get("client_secret").asText();
        String verifier = verifier();
        String requestId = authorize(clientId, REDIRECT, challenge(verifier), "s", "mcp:read");
        String code = approve(requestId, projectA, "READ_ONLY").getQueryParams().getFirst("code");

        MvcResult noSecret = mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                                "client_id", clientId, "code_verifier", verifier)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(objectMapper.readTree(noSecret.getResponse().getContentAsString()).get("error").asText())
                .isEqualTo("invalid_client");

        JsonNode tokens = token(form("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", clientId, "client_secret", secret, "code_verifier", verifier));
        assertThat(tokens.get("scope").asText()).isEqualTo("mcp:read");
    }

    @Test
    void revocationEndpointEndsTheGrant() throws Exception {
        String clientId = registerClient(List.of(REDIRECT), "none").get("client_id").asText();
        JsonNode tokens = connect(clientId, projectA, "READ_ONLY");

        mockMvc.perform(post("/oauth/revoke")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form("token", tokens.get("refresh_token").asText(), "client_id", clientId)))
                .andExpect(status().isOk());

        rpcStatus(tokens.get("access_token").asText(), "tools/list", 401);
        assertThat(grantRepository.findAll().stream()
                .filter(g -> g.getProjectId().equals(projectA) && g.getRevokedAt() == null
                        && g.getActivatedAt() != null)).isEmpty();

        // An unknown token is not an error (RFC 7009 §2.2).
        mockMvc.perform(post("/oauth/revoke")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form("token", "nonsense", "client_id", clientId)))
                .andExpect(status().isOk());
    }

    @Test
    void apiKeysKeepWorkingNextToOAuth() throws Exception {
        MvcResult key = mockMvc.perform(post("/api/v1/projects/" + projectA + "/api-keys")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mcp key\",\"scope\":\"READ_ONLY\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String apiKey = objectMapper.readTree(key.getResponse().getContentAsString()).get("key").asText();
        assertThat(rpc(apiKey, "tools/list", Map.of()).has("result")).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private JsonNode connect(String clientId, UUID projectId, String scope) throws Exception {
        String verifier = verifier();
        String requestId = authorize(clientId, REDIRECT, challenge(verifier), "s", "mcp:read mcp:write");
        String code = approve(requestId, projectId, scope).getQueryParams().getFirst("code");
        return token(form("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", verifier));
    }

    private JsonNode registerClient(List<String> redirects, String authMethod) throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "client_name", "Claude",
                                "redirect_uris", redirects,
                                "grant_types", List.of("authorization_code", "refresh_token"),
                                "response_types", List.of("code"),
                                "token_endpoint_auth_method", authMethod))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Opens /oauth/authorize as a browser would and returns the consent request it lands on. */
    private String authorize(String clientId, String redirectUri, String challenge, String state, String scope)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth/authorize")
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256")
                        .param("state", state)
                        .param("scope", scope)
                        .param("resource", BASE + "/mcp"))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith(BASE + "/oauth/consent?request=");
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("request");
    }

    private UriComponents approve(String requestId, UUID projectId, String scope) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/oauth/requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectId, "scope", scope))))
                .andExpect(status().isOk())
                .andReturn();
        String url = objectMapper.readTree(result.getResponse().getContentAsString()).get("redirectUrl").asText();
        return UriComponentsBuilder.fromUriString(url).build();
    }

    private JsonNode token(String form) throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode tokenError(String form) throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form))
                .andExpect(status().isBadRequest())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String form(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        StringBuilder out = new StringBuilder();
        values.forEach((k, v) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String verifier() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path, String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private JsonNode callTool(String bearer, String name, Map<String, Object> arguments) throws Exception {
        JsonNode response = rpc(bearer, "tools/call", Map.of("name", name, "arguments", arguments));
        assertThat(response.has("result")).as(response.toString()).isTrue();
        return response.get("result");
    }

    private static String text(JsonNode callToolResult) {
        return callToolResult.get("content").get(0).get("text").asText();
    }

    private JsonNode rpc(String bearer, String method, Map<String, Object> params) throws Exception {
        MvcResult result = mockMvc.perform(mcpPost(rpcBody(method, params)).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void rpcStatus(String bearer, String method, int expected) throws Exception {
        mockMvc.perform(mcpPost(rpcBody(method, Map.of())).header("Authorization", "Bearer " + bearer))
                .andExpect(status().is(expected));
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

    private String email;

    private JsonNode register(String address, String orgName) throws Exception {
        if (email == null) {
            email = address;
        }
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email(address).password("Test1234!").organizationName(orgName).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "Test1234!"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID createProject(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder().name(name).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
