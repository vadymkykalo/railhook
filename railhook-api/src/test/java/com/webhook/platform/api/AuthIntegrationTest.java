package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.*;
import com.webhook.platform.api.security.JwtUtil;
import com.webhook.platform.api.service.EmailService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@AutoConfigureMockMvc
public class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    // Mocked to capture the plaintext token; the DB holds only its hash.
    @MockitoBean
    private EmailService emailService;

    // Otherwise the mock reports email disabled and registration completes already verified.
    @BeforeEach
    void enableEmail() {
        when(emailService.isEnabled()).thenReturn(true);
    }

    @Test
    public void testRegisterLoginAndGetCurrentUser() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("test@example.com")
                .password("Test1234!")
                .organizationName("Test Company")
                .build();

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(cookie().exists("refresh_token"))
                .andReturn();

        AuthResponse authResponse = objectMapper.readValue(
                registerResult.getResponse().getContentAsString(),
                AuthResponse.class
        );

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + authResponse.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("test@example.com"))
                .andExpect(jsonPath("$.user.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.organization.name").value("Test Company"))
                .andExpect(jsonPath("$.role").value("OWNER"));

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationEmail(eq("test@example.com"), tokenCaptor.capture());
        String verificationToken = tokenCaptor.getValue();

        User user = userRepository.findByEmail("test@example.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(user.getVerificationToken())
                .isNotEqualTo(verificationToken);

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .param("token", verificationToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + authResponse.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));

        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("Test1234!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(cookie().exists("refresh_token"));
    }

    @Test
    public void testLoginWithInvalidCredentials() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .email("nonexistent@example.com")
                .password("WrongPass1!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    // Guards the missing typ claim that let an access token mint a refresh token.
    @Test
    public void testAccessTokenRejectedByRefreshEndpoint() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        AuthResponse tokens = registerAndCaptureTokens("jwt-token-type-access-as-refresh@example.com");

        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(tokens.getAccessToken());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRefreshTokenRejectedAsBearerCredential() throws Exception {
        AuthResponse tokens = registerAndCaptureTokens("jwt-token-type-refresh-as-bearer@example.com");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokens.getRefreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testLoginRefreshAccessCycleStillWorks() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        AuthResponse tokens = registerAndCaptureTokens("jwt-token-type-happy-path@example.com");

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", tokens.getRefreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().exists("refresh_token"))
                .andReturn();

        AuthResponse refreshed = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(), AuthResponse.class);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + refreshed.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("jwt-token-type-happy-path@example.com"));
    }

    // The CLI keeps its old token when the body has none; replaying it revoked every session.
    @Test
    public void testRefreshWithBodyTokenReturnsTheRotatedTokenInTheBody() throws Exception {
        AuthResponse tokens = registerAndCaptureTokens("refresh-body-client@example.com");

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(tokens.getRefreshToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();
        AuthResponse refreshed = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(), AuthResponse.class);

        org.junit.jupiter.api.Assertions.assertNotNull(refreshed.getRefreshToken(),
                "a body client must receive the rotated refresh token");
        org.junit.jupiter.api.Assertions.assertNotEquals(tokens.getRefreshToken(), refreshed.getRefreshToken());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshed.getRefreshToken()))))
                .andExpect(status().isOk());
    }

    @Test
    public void testReplayingRotatedRefreshTokenRevokesTokenFamily() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        AuthResponse tokens = registerAndCaptureTokens("jwt-token-type-reuse-detection@example.com");
        String rotatedAwayRefreshToken = tokens.getRefreshToken();
        var userId = jwtUtil.getUserIdFromToken(rotatedAwayRefreshToken);

        // The Redis blacklist is mocked, so drive it directly.
        String rotatedJti = jwtUtil.getJtiFromToken(rotatedAwayRefreshToken);
        when(tokenBlacklistService.isBlacklisted(eq(rotatedJti))).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", rotatedAwayRefreshToken)))
                .andExpect(status().isUnauthorized());

        verify(tokenBlacklistService).revokeAllUserTokens(eq(userId));
    }

    @Test
    public void testReplayingRotatedRefreshTokenAlsoEndsTheRotatedSuccessor() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        AuthResponse tokens = registerAndCaptureTokens("refresh-reuse-ends-sessions@example.com");
        String original = tokens.getRefreshToken();

        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", original)))
                .andExpect(status().isOk())
                .andReturn();
        String successor = rotated.getResponse().getCookie("refresh_token").getValue();

        // The Redis blacklist is mocked; mark the rotated-away jti as refresh just did.
        when(tokenBlacklistService.isBlacklisted(eq(jwtUtil.getJtiFromToken(original)))).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", original)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", successor)))
                .andExpect(status().isUnauthorized());
    }

    private AuthResponse registerAndCaptureTokens(String email) throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName("Test Company")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class);

        Cookie refreshCookie = result.getResponse().getCookie("refresh_token");
        response.setRefreshToken(refreshCookie != null ? refreshCookie.getValue() : null);
        return response;
    }
}
