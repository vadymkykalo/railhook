package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.email.enabled=false")
public class RegistrationWithoutEmailIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Test
    public void registrationCompletesVerifiedWhenEmailIsDisabled() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("nomail@example.com")
                .password("Test1234!")
                .organizationName("No Mail Co")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerified").value(true));

        User stored = userRepository.findByEmail("nomail@example.com").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(stored.getEmailVerified()).isTrue();
    }

    @Test
    public void noVerificationTokenIsIssuedWhenEmailIsDisabled() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("notoken@example.com")
                .password("Test1234!")
                .organizationName("No Token Co")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // An undeliverable token is still a live credential /verify-email would honour.
        User stored = userRepository.findByEmail("notoken@example.com").orElseThrow();
        assertThat(stored.getVerificationToken()).isNull();
        assertThat(stored.getVerificationTokenExpiresAt()).isNull();
    }

    @Test
    public void aPasswordRegistrationNamesItsOwnFirstProject() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("firstproject@example.com")
                .password("Test1234!")
                .organizationName("First Project Co")
                .build();

        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asText();

        // Only a Google sign-up, which asked nothing, gets a first project.
        mockMvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    public void theDashboardIsUsableImmediatelyAfterRegistering() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("usable@example.com")
                .password("Test1234!")
                .organizationName("Usable Co")
                .build();

        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(body).get("accessToken").asText();

        // VerificationGate derives emailVerified from status alone; /me has no field of its own.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));
    }
}
