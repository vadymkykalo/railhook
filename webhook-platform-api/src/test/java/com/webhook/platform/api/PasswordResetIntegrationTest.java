package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.*;
import com.webhook.platform.api.service.EmailService;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
public class PasswordResetIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    // Mocked so tests can capture the plaintext reset token EmailService would have
    // emailed to the user (the DB column now holds only CryptoUtils.hashApiKey(token),
    // so the raw token is no longer recoverable by reading the row back).
    @MockitoBean
    private EmailService emailService;

    private static final String EMAIL = "reset-test@example.com";
    private static final String ORIGINAL_PASSWORD = "Original1!";
    private static final String NEW_PASSWORD = "NewPass1!x";

    @BeforeEach
    void registerUser() throws Exception {
        if (userRepository.findByEmail(EMAIL).isEmpty()) {
            RegisterRequest req = RegisterRequest.builder()
                    .email(EMAIL)
                    .password(ORIGINAL_PASSWORD)
                    .organizationName("Reset Test Org")
                    .build();

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }
    }

    /**
     * Triggers forgot-password and returns the plaintext token EmailService was asked
     * to send — the only place the raw token is observable now that the DB stores a hash.
     */
    private String requestResetAndCaptureToken() throws Exception {
        org.mockito.Mockito.clearInvocations(emailService);

        ForgotPasswordRequest forgotReq = ForgotPasswordRequest.builder()
                .email(EMAIL)
                .build();

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forgotReq)))
                .andExpect(status().isOk());

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(eq(EMAIL), tokenCaptor.capture());
        return tokenCaptor.getValue();
    }

    // ---------------------------------------------------------------
    // Happy path: forgot → reset → login with new password
    // ---------------------------------------------------------------

    @Test
    void testForgotAndResetPassword_fullFlow() throws Exception {
        // 1. Request password reset, capturing the plaintext token that would be emailed
        String resetToken = requestResetAndCaptureToken();

        // 2. Verify only a HASH of the token was saved in DB — never the plaintext
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getPasswordResetToken()).isNotNull();
        assertThat(user.getPasswordResetToken()).isNotEqualTo(resetToken);
        assertThat(user.getPasswordResetToken()).isEqualTo(CryptoUtils.hashApiKey(resetToken));
        assertThat(user.getPasswordResetTokenExpiresAt()).isNotNull();
        assertThat(user.getPasswordResetTokenExpiresAt()).isAfter(java.time.Instant.now());

        // 3. Reset password using the token
        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword(NEW_PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isOk());

        // 4. Verify token was cleared
        User updatedUser = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(updatedUser.getPasswordResetToken()).isNull();
        assertThat(updatedUser.getPasswordResetTokenExpiresAt()).isNull();

        // 5. Login with OLD password should fail
        LoginRequest oldLogin = LoginRequest.builder()
                .email(EMAIL)
                .password(ORIGINAL_PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oldLogin)))
                .andExpect(status().isUnauthorized());

        // 6. Login with NEW password should succeed
        LoginRequest newLogin = LoginRequest.builder()
                .email(EMAIL)
                .password(NEW_PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    // ---------------------------------------------------------------
    // Forgot password for non-existent email — still returns 200
    // (anti-enumeration)
    // ---------------------------------------------------------------

    @Test
    void testForgotPassword_nonExistentEmail_returns200() throws Exception {
        ForgotPasswordRequest req = ForgotPasswordRequest.builder()
                .email("nobody@nowhere.com")
                .build();

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------
    // Reset with invalid token — 400
    // ---------------------------------------------------------------

    @Test
    void testResetPassword_invalidToken_returns400() throws Exception {
        ResetPasswordRequest req = ResetPasswordRequest.builder()
                .token("completely_bogus_token")
                .newPassword(NEW_PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------
    // Reset with expired token — 400
    // ---------------------------------------------------------------

    @Test
    void testResetPassword_expiredToken_returns400() throws Exception {
        // 1. Request reset
        String resetToken = requestResetAndCaptureToken();

        // 2. Manually expire the token
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        user.setPasswordResetTokenExpiresAt(java.time.Instant.now().minusSeconds(3600));
        userRepository.save(user);

        // 3. Attempt reset — should fail
        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword(NEW_PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------
    // Token is single-use — second reset with same token fails
    // ---------------------------------------------------------------

    @Test
    void testResetPassword_tokenSingleUse() throws Exception {
        // 1. Request reset
        String resetToken = requestResetAndCaptureToken();

        // 2. First reset — succeeds
        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword("FirstReset1!")
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isOk());

        // 3. Second reset with same token — fails
        ResetPasswordRequest secondReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword("SecondReset1!")
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondReq)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------
    // Validation: weak password rejected
    // ---------------------------------------------------------------

    @Test
    void testResetPassword_weakPassword_returns400() throws Exception {
        // 1. Request reset
        String resetToken = requestResetAndCaptureToken();

        // 2. Reset with weak password
        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword("weak")
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------
    // Validation: forgot-password with invalid email format
    // ---------------------------------------------------------------

    @Test
    void testForgotPassword_invalidEmailFormat_returns400() throws Exception {
        ForgotPasswordRequest req = ForgotPasswordRequest.builder()
                .email("not-an-email")
                .build();

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
