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

    // Mocked so tests can capture the plaintext verification token EmailService would
    // have emailed to the user (the DB column now holds only
    // CryptoUtils.hashApiKey(token), so the raw token can't be read back from the row).
    @MockitoBean
    private EmailService emailService;

    // This class exercises the email-verification journey, which only exists when
    // there is an email channel to verify over. The mock would otherwise report
    // email as disabled and registration would complete already-verified — see
    // RegistrationWithoutEmailIntegrationTest for that path.
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

        // The DB column now holds only a hash of the verification token; the
        // plaintext is only ever handed to EmailService, which is mocked here so the
        // test can capture it.
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

    /**
     * An access token must not be exchangeable at /auth/refresh. Before the fix,
     * JwtUtil stamped no "typ" claim on either token, so AuthService.refreshToken happily
     * accepted an access token and minted a brand new (renewable) refresh token from it --
     * turning a 15-minute leak into a permanent session.
     */
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

    /**
     * The reverse direction must be a deliberate rejection (typ != access), not the
     * old accidental NPE on the missing "organizationId" claim.
     */
    @Test
    public void testRefreshTokenRejectedAsBearerCredential() throws Exception {
        AuthResponse tokens = registerAndCaptureTokens("jwt-token-type-refresh-as-bearer@example.com");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokens.getRefreshToken()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The legitimate login -> refresh -> access cycle must keep working after the
     * type-claim enforcement is added.
     */
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

    /**
     * Reuse detection: replaying a refresh token that has already been rotated away
     * (i.e. it is blacklisted) must not just 401 -- it must revoke the whole token family
     * via tokenBlacklistService.revokeAllUserTokens, since replay of a rotated-away token
     * is the signature of a stolen refresh token racing the legitimate client.
     */
    @Test
    public void testReplayingRotatedRefreshTokenRevokesTokenFamily() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        AuthResponse tokens = registerAndCaptureTokens("jwt-token-type-reuse-detection@example.com");
        String rotatedAwayRefreshToken = tokens.getRefreshToken();
        var userId = jwtUtil.getUserIdFromToken(rotatedAwayRefreshToken);

        // Simulate that this exact refresh token was already rotated away (or revoked) --
        // the real blacklist is Redis-backed and mocked out in AbstractIntegrationTest, so
        // we drive the mock directly rather than depending on real TTL/storage behavior.
        String rotatedJti = jwtUtil.getJtiFromToken(rotatedAwayRefreshToken);
        when(tokenBlacklistService.isBlacklisted(eq(rotatedJti))).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", rotatedAwayRefreshToken)))
                .andExpect(status().isUnauthorized());

        verify(tokenBlacklistService).revokeAllUserTokens(eq(userId));
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
