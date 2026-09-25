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

    // Mocked to capture the plaintext token; the DB holds only its hash.
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

    @Test
    void testForgotAndResetPassword_fullFlow() throws Exception {
        String resetToken = requestResetAndCaptureToken();

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getPasswordResetToken()).isNotNull();
        assertThat(user.getPasswordResetToken()).isNotEqualTo(resetToken);
        assertThat(user.getPasswordResetToken()).isEqualTo(CryptoUtils.hashApiKey(resetToken));
        assertThat(user.getPasswordResetTokenExpiresAt()).isNotNull();
        assertThat(user.getPasswordResetTokenExpiresAt()).isAfter(java.time.Instant.now());

        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword(NEW_PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isOk());

        User updatedUser = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(updatedUser.getPasswordResetToken()).isNull();
        assertThat(updatedUser.getPasswordResetTokenExpiresAt()).isNull();

        LoginRequest oldLogin = LoginRequest.builder()
                .email(EMAIL)
                .password(ORIGINAL_PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oldLogin)))
                .andExpect(status().isUnauthorized());

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

    @Test
    void testResetPassword_expiredToken_returns400() throws Exception {
        String resetToken = requestResetAndCaptureToken();

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        user.setPasswordResetTokenExpiresAt(java.time.Instant.now().minusSeconds(3600));
        userRepository.save(user);

        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword(NEW_PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testResetPassword_tokenSingleUse() throws Exception {
        String resetToken = requestResetAndCaptureToken();

        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword("FirstReset1!")
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isOk());

        ResetPasswordRequest secondReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword("SecondReset1!")
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testResetPassword_weakPassword_returns400() throws Exception {
        String resetToken = requestResetAndCaptureToken();

        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .token(resetToken)
                .newPassword("weak")
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isBadRequest());
    }

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
