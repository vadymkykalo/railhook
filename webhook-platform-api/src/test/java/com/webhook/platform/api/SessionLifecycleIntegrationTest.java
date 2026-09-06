package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.repository.UserSessionRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.security.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The session lifecycle against a real database.
 *
 * <p>The unit tests pin the decisions; this pins the wiring, which is where a session feature
 * built on top of stateless JWTs goes wrong. In particular: the {@code user_sessions} row is
 * inserted with a caller-assigned id (it has to be — the id is the {@code sid} claim of the very
 * token being minted, so it exists before the row does), the jti actually rotates on refresh,
 * and a revoked row refuses the next refresh <em>without</em> Redis, which is mocked out here.
 * That last one is the point of keeping a durable half at all: a Redis flush must not silently
 * un-revoke every session anybody signed out.
 */
@AutoConfigureMockMvc
public class SessionLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserSessionRepository userSessionRepository;

    @Test
    @DisplayName("signing in records a session, and it is the one the list flags as current")
    void registrationOpensASessionTheListCanSee() throws Exception {
        AuthResponse tokens = register("session-lifecycle-list@example.com");

        UUID sessionId = jwtUtil.getSessionIdFromToken(tokens.getAccessToken());
        assertThat(sessionId)
                .as("the access token has to name its session, or revoking one could not reach it")
                .isNotNull();
        assertThat(jwtUtil.getSessionIdFromToken(tokens.getRefreshToken())).isEqualTo(sessionId);

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + tokens.getAccessToken())
                        .cookie(new Cookie("refresh_token", tokens.getRefreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(sessionId.toString()))
                .andExpect(jsonPath("$[0].client").value("WEB"))
                .andExpect(jsonPath("$[0].current").value(true));
    }

    @Test
    @DisplayName("the list never carries token material")
    void listCarriesNoCredential() throws Exception {
        AuthResponse tokens = register("session-lifecycle-no-secrets@example.com");
        String storedJti = userSessionRepository
                .findByRefreshTokenJti(jwtUtil.getJtiFromToken(tokens.getRefreshToken()))
                .orElseThrow()
                .getRefreshTokenJti();

        String body = mockMvc.perform(get("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + tokens.getAccessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        /* Any access token can read this list, so it must not be a place a stolen short-lived
           credential can be traded up for a long-lived one. */
        assertThat(body).doesNotContain(storedJti);
        assertThat(body).doesNotContain(tokens.getRefreshToken());
    }

    @Test
    @DisplayName("a refresh rotates the session onto the new token rather than opening a second")
    void refreshRotatesInPlace() throws Exception {
        AuthResponse tokens = register("session-lifecycle-rotate@example.com");
        UUID sessionId = jwtUtil.getSessionIdFromToken(tokens.getRefreshToken());
        String firstJti = jwtUtil.getJtiFromToken(tokens.getRefreshToken());

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", tokens.getRefreshToken())))
                .andExpect(status().isOk())
                .andReturn();
        String newRefreshToken = refreshCookie(refreshed);

        assertThat(jwtUtil.getSessionIdFromToken(newRefreshToken))
                .as("still the same session — refreshing is not signing in again")
                .isEqualTo(sessionId);

        var row = userSessionRepository.findById(sessionId).orElseThrow();
        assertThat(row.getRefreshTokenJti())
                .isEqualTo(jwtUtil.getJtiFromToken(newRefreshToken))
                .isNotEqualTo(firstJti);
        assertThat(userSessionRepository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(
                        row.getUserId(), java.time.Instant.now()))
                .hasSize(1);
    }

    @Test
    @DisplayName("a superseded refresh token is refused by the row, with no help from Redis")
    void supersededTokenIsRefusedWithoutRedis() throws Exception {
        AuthResponse tokens = register("session-lifecycle-superseded@example.com");
        String supersededToken = tokens.getRefreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", supersededToken)))
                .andExpect(status().isOk());

        /* TokenBlacklistService is a mock here and answers "not blacklisted" to everything, so
           the only thing standing between a replayed token and a fresh pair is the session row
           no longer naming its jti. That is exactly the situation after a Redis restart. */
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", supersededToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("revoking a session ends it, and the revoked token cannot be refreshed")
    void revokedSessionCannotRefresh() throws Exception {
        AuthResponse tokens = register("session-lifecycle-revoke@example.com");
        UUID sessionId = jwtUtil.getSessionIdFromToken(tokens.getAccessToken());

        mockMvc.perform(delete("/api/v1/auth/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + tokens.getAccessToken()))
                .andExpect(status().isNoContent());

        assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNotNull();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", tokens.getRefreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("one user cannot revoke another's session")
    void cannotRevokeSomebodyElsesSession() throws Exception {
        AuthResponse victim = register("session-lifecycle-victim@example.com");
        AuthResponse attacker = register("session-lifecycle-attacker@example.com");
        UUID victimSessionId = jwtUtil.getSessionIdFromToken(victim.getAccessToken());

        /* user_sessions carries no @TenantId, so nothing confines this query structurally — the
           (id, userId) lookup in UserSessionService is the entire ownership check, which is why
           it is asserted here rather than assumed. */
        mockMvc.perform(delete("/api/v1/auth/sessions/" + victimSessionId)
                        .header("Authorization", "Bearer " + attacker.getAccessToken()))
                .andExpect(status().isNotFound());

        assertThat(userSessionRepository.findById(victimSessionId).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("sign out everywhere revokes every session the account has")
    void signOutEverywhere() throws Exception {
        AuthResponse first = register("session-lifecycle-everywhere@example.com");
        UUID userId = jwtUtil.getUserIdFromToken(first.getAccessToken());

        mockMvc.perform(post("/api/v1/auth/sessions/revoke-all")
                        .header("Authorization", "Bearer " + first.getAccessToken()))
                .andExpect(status().isNoContent());

        assertThat(userSessionRepository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(
                        userId, java.time.Instant.now()))
                .isEmpty();
    }

    private AuthResponse register(String email) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName("Session Co")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class);
        // The refresh token is stripped from the body and set as an httpOnly cookie, so the
        // tests have to read it back the way a browser would.
        response.setRefreshToken(refreshCookie(result));
        return response;
    }

    private String refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }
}
