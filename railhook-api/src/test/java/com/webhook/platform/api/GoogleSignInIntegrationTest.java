package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.UserIdentityRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.service.EmailService;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Continue with Google" end to end, against a stand-in for Google's token endpoint and key set:
 * start, callback, the one-time code the dashboard exchanges, and the session that comes out.
 */
class GoogleSignInIntegrationTest extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "test-client.apps.googleusercontent.com";
    private static final String STATE_COOKIE = "railhook_oauth_state";
    private static final String HANDOFF_COOKIE = "railhook_signin_handoff";
    private static final KeyPair GOOGLE_KEYS = rsa();
    private static final Map<String, String> LAST_TOKEN_REQUEST = new ConcurrentHashMap<>();
    private static volatile String nextIdToken;
    private static final HttpServer GOOGLE = startGoogle();

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + GOOGLE.getAddress().getPort();
        registry.add("google.oauth.client-id", () -> CLIENT_ID);
        registry.add("google.oauth.client-secret", () -> "test-client-secret");
        registry.add("google.oauth.token-uri", () -> base + "/token");
        registry.add("google.oauth.jwks-uri", () -> base + "/jwks");
        registry.add("app.base-url", () -> "http://localhost:8080");
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    /** What the browser that went through the last {@link #callback} holds. */
    private Cookie handoffCookie;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @MockitoBean
    private EmailService emailService;

    @Test
    void theDashboardIsToldGoogleSignInIsAvailable() throws Exception {
        mockMvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google").value(true));
    }

    @Test
    void startSendsTheBrowserToGoogleWithPkce() throws Exception {
        SignInStart start = start("/admin/projects");

        assertThat(start.googleUrl()).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        Map<String, String> query = query(start.googleUrl());
        assertThat(query).containsEntry("client_id", CLIENT_ID)
                .containsEntry("redirect_uri", "http://localhost:8080/api/v1/auth/oauth/google/callback")
                .containsEntry("response_type", "code")
                .containsEntry("code_challenge_method", "S256");
        assertThat(query.get("scope")).contains("openid").contains("email").contains("profile");
        assertThat(query.get("code_challenge")).isNotBlank();
        assertThat(start.cookie().isHttpOnly()).isTrue();
    }

    @Test
    void aNewPersonGetsAnAccountAndAnOrganizationInOneGo() throws Exception {
        SignInStart start = start("/admin/projects");
        nextIdToken = idToken(start.nonce(), Map.of(
                "sub", "g-grace", "email", "grace@hopper.dev", "name", "Grace Hopper",
                "given_name", "Grace", "hd", "hopper.dev"));

        String landing = callback(start);
        assertThat(landing).startsWith("/auth/callback?");
        Map<String, String> query = query(landing);
        assertThat(query).containsEntry("new", "1").containsEntry("returnTo", "/admin/projects");
        assertThat(LAST_TOKEN_REQUEST).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_secret", "test-client-secret")
                .containsKey("code_verifier");

        String accessToken = exchange(query.get("code"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("grace@hopper.dev"))
                .andExpect(jsonPath("$.user.fullName").value("Grace Hopper"))
                .andExpect(jsonPath("$.user.status").value("ACTIVE"))
                .andExpect(jsonPath("$.organization.name").value("Hopper"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.hasPassword").value(false));

        assertThat(userIdentityRepository.findByProviderAndSubject("google", "g-grace")).isPresent();

        // The same start a password registration gets: one project, so the dashboard has
        // something to open.
        mockMvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("My first project"));
    }

    @Test
    void anAccountThatSignedUpWithAPasswordIsLinkedNotDuplicated() throws Exception {
        registerWithPassword("linus@kernel.dev", "Kernel");

        SignInStart start = start("/admin/projects");
        nextIdToken = idToken(start.nonce(), Map.of("sub", "g-linus", "email", "linus@kernel.dev"));
        Map<String, String> query = query(callback(start));
        assertThat(query).doesNotContainKey("new");

        String accessToken = exchange(query.get("code"));
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.organization.name").value("Kernel"))
                .andExpect(jsonPath("$.hasPassword").value(true));

        // The password still works: linking adds a way in, it does not take one away.
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("linus@kernel.dev", "Password1234!"))))
                .andExpect(status().isOk());
    }

    @Test
    void aCaseVariantOfTheAddressCannotBreakTheOwnersGoogleSignIn() throws Exception {
        registerWithPassword("grace@navy.dev", "Navy");

        // Someone tries to register the owner's address in other letters. It used to succeed, and
        // the owner's Google sign-in then found two accounts and failed on every attempt.
        RegisterRequest variant = RegisterRequest.builder()
                .email("Grace@Navy.dev").password("Password1234!").organizationName("Squatter").build();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(variant)))
                .andExpect(status().isConflict());

        SignInStart start = start("/admin/projects");
        nextIdToken = idToken(start.nonce(), Map.of("sub", "g-grace-navy", "email", "GRACE@navy.dev"));
        Map<String, String> query = query(callback(start));
        assertThat(query).doesNotContainKey("new");

        String accessToken = exchange(query.get("code"));
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.organization.name").value("Navy"));
    }

    @Test
    void anUnverifiedAccountLosesAPasswordNobodyProvedWasTheirs() throws Exception {
        // Someone registers the victim's address with a password of their own and never verifies
        // it. When the real owner arrives through Google, the account must not keep a password the
        // squatter knows.
        when(emailService.isEnabled()).thenReturn(true);
        registerWithPassword("target@victim.dev", "Squatted");
        when(emailService.isEnabled()).thenReturn(false);

        SignInStart start = start("/admin/projects");
        nextIdToken = idToken(start.nonce(), Map.of("sub", "g-target", "email", "target@victim.dev"));
        exchange(query(callback(start)).get("code"));

        User user = userRepository.findByEmail("target@victim.dev").orElseThrow();
        assertThat(user.getEmailVerified()).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getPasswordHash()).isNull();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("target@victim.dev", "Password1234!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aReturningGoogleUserSignsIntoTheSameAccountEvenAfterChangingAddress() throws Exception {
        SignInStart first = start("/admin/projects");
        nextIdToken = idToken(first.nonce(), Map.of("sub", "g-ada", "email", "ada@analytical.dev"));
        exchange(query(callback(first)).get("code"));
        User created = userRepository.findByEmail("ada@analytical.dev").orElseThrow();

        SignInStart second = start("/admin/projects");
        nextIdToken = idToken(second.nonce(), Map.of("sub", "g-ada", "email", "ada.lovelace@analytical.dev"));
        Map<String, String> query = query(callback(second));
        assertThat(query).doesNotContainKey("new");

        String accessToken = exchange(query.get("code"));
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.user.id").value(created.getId().toString()));
    }

    @Test
    void aGoogleOnlyAccountHasNoPasswordToGuess() throws Exception {
        SignInStart start = start("/admin/projects");
        nextIdToken = idToken(start.nonce(), Map.of("sub", "g-nopass", "email", "nopass@example.dev"));
        exchange(query(callback(start)).get("code"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("nopass@example.dev", "anything-at-all"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSignInCodeWorksOnce() throws Exception {
        SignInStart start = start("/admin/projects");
        nextIdToken = idToken(start.nonce(), Map.of("sub", "g-once", "email", "once@example.dev"));
        String code = query(callback(start)).get("code");

        exchange(code);
        mockMvc.perform(post("/api/v1/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON)
                        .cookie(handoffCookie)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSignInCodeOnlyWorksInTheBrowserGoogleSentBack() throws Exception {
        // Login CSRF: someone finishes a Google sign-in of their own, stops before the dashboard
        // spends the code, and sends the link to someone else. Opened there, it must not sign that
        // person into the sender's account.
        SignInStart attacker = start("/admin/projects");
        nextIdToken = idToken(attacker.nonce(), Map.of("sub", "g-mallory", "email", "mallory@example.dev"));
        String code = query(callback(attacker)).get("code");
        Cookie attackersBrowser = handoffCookie;

        mockMvc.perform(post("/api/v1/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // A victim who went through a sign-in of their own holds a handoff cookie too, for their code.
        SignInStart victim = start("/admin/projects");
        nextIdToken = idToken(victim.nonce(), Map.of("sub", "g-victim", "email", "victim@example.dev"));
        callback(victim);
        mockMvc.perform(post("/api/v1/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON)
                        .cookie(handoffCookie)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());

        // The refusals did not spend the code: the browser it was issued to still signs in.
        handoffCookie = attackersBrowser;
        exchange(code);
    }

    @Test
    void aCallbackWhoseStateDoesNotMatchTheCookieIsRefused() throws Exception {
        SignInStart start = start("/admin/projects");

        MvcResult result = mockMvc.perform(get("/api/v1/auth/oauth/google/callback")
                        .param("code", "google-code").param("state", "somebody-elses-state")
                        .cookie(start.cookie()))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/login?error=google_state");
    }

    @Test
    void aCallbackWithoutTheCookieIsRefused() throws Exception {
        SignInStart start = start("/admin/projects");

        MvcResult result = mockMvc.perform(get("/api/v1/auth/oauth/google/callback")
                        .param("code", "google-code").param("state", start.state()))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/login?error=google_state");
    }

    @Test
    void choosingNotToShareTheAccountLandsBackOnTheLoginPage() throws Exception {
        SignInStart start = start("/admin/projects");

        MvcResult result = mockMvc.perform(get("/api/v1/auth/oauth/google/callback")
                        .param("error", "access_denied").param("state", start.state())
                        .cookie(start.cookie()))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/login?error=google_denied");
    }

    @Test
    void anAddressGoogleHasNotVerifiedIsRefused() throws Exception {
        SignInStart start = start("/admin/projects");
        nextIdToken = idToken(start.nonce(), Map.of("sub", "g-unverified", "email", "unverified@example.dev",
                "email_verified", false));

        assertThat(callback(start)).isEqualTo("/login?error=google_unverified_email");
        assertThat(userRepository.findByEmail("unverified@example.dev")).isEmpty();
    }

    @Test
    void aReturnAddressOffThisSiteIsReplaced() throws Exception {
        SignInStart start = start("https://evil.example/admin");
        nextIdToken = idToken(start.nonce(), Map.of("sub", "g-evil", "email", "evil-return@example.dev"));

        assertThat(query(callback(start))).containsEntry("returnTo", "/admin/dashboard");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private record SignInStart(String googleUrl, String state, String nonce, Cookie cookie) {}

    private SignInStart start(String returnTo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/oauth/google/start")
                        .param("intent", "login").param("returnTo", returnTo))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        Cookie cookie = result.getResponse().getCookie(STATE_COOKIE);
        assertThat(cookie).as("state cookie").isNotNull();
        Map<String, String> query = query(location);
        return new SignInStart(location, query.get("state"), query.get("nonce"), cookie);
    }

    /**
     * Google's redirect back, carrying the state it was given. Returns where the API sends the
     * browser, and keeps the handoff cookie that browser now holds for {@link #exchange}.
     */
    private String callback(SignInStart start) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/oauth/google/callback")
                        .param("code", "google-code-" + start.state()).param("state", start.state())
                        .cookie(start.cookie()))
                .andExpect(status().isFound())
                .andReturn();
        Cookie cleared = result.getResponse().getCookie(STATE_COOKIE);
        assertThat(cleared).as("state cookie is cleared").isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        handoffCookie = result.getResponse().getCookie(HANDOFF_COOKIE);
        return result.getResponse().getHeader("Location");
    }

    private String exchange(String code) throws Exception {
        assertThat(handoffCookie).as("handoff cookie from the callback").isNotNull();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(handoffCookie)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andReturn();
        assertThat(result.getResponse().getCookie("refresh_token")).isNotNull();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    private void registerWithPassword(String email, String organizationName) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(email).password("Password1234!").organizationName(organizationName).build();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> values = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((key, list) -> values.put(key, URLDecoder.decode(list.get(0), StandardCharsets.UTF_8)));
        return values;
    }

    private static String idToken(String nonce, Map<String, Object> overrides) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", CLIENT_ID);
        claims.put("email_verified", true);
        claims.put("nonce", nonce);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(3600).getEpochSecond());
        claims.putAll(overrides);
        return Jwts.builder().header().keyId("test-key").and()
                .claims(claims)
                .signWith(GOOGLE_KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static HttpServer startGoogle() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST_TOKEN_REQUEST.clear();
                for (String pair : form.split("&")) {
                    String[] kv = pair.split("=", 2);
                    LAST_TOKEN_REQUEST.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                            kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
                }
                respond(exchange, "{\"access_token\":\"ya29.test\",\"token_type\":\"Bearer\",\"expires_in\":3599,"
                        + "\"id_token\":\"" + nextIdToken + "\"}");
            });
            server.createContext("/jwks", exchange -> respond(exchange, jwks()));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String jwks() {
        RSAPublicKey key = (RSAPublicKey) GOOGLE_KEYS.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"test-key\",\"n\":\""
                + base64Url(key.getModulus()) + "\",\"e\":\"" + base64Url(key.getPublicExponent()) + "\"}]}";
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
