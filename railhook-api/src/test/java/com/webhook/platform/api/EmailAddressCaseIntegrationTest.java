package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailAddressCaseIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Password1234!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    @MockitoBean private EmailService emailService;

    @Test
    void aCaseVariantOfARegisteredAddressIsRefusedAsExisting() throws Exception {
        register("victim@corp.example").andExpect(status().isCreated());

        register("Victim@Corp.example")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void anAddressIsStoredInLowerCase_andSignInIgnoresCase() throws Exception {
        register("Grace.Hopper@Navy.example").andExpect(status().isCreated());

        assertThat(userRepository.findByEmail("grace.hopper@navy.example")).isPresent();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("GRACE.HOPPER@navy.example", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void aPasswordResetFindsTheAccountWhateverTheCase() throws Exception {
        register("reset.me@corp.example").andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Reset.Me@Corp.example\"}"))
                .andExpect(status().isOk());

        verify(emailService).sendPasswordResetEmail(eq("reset.me@corp.example"), anyString());
    }

    private ResultActions register(String email) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(email).password(PASSWORD).organizationName("Org of " + email).build();
        return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }
}
