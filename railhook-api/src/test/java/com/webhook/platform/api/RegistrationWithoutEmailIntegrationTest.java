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

/**
 * With {@code app.email.enabled=false} — the shipped default — there is no channel
 * that can carry a verification token to the person who just registered. Leaving
 * them PENDING_VERIFICATION is then a gate with no key: every write button in the
 * dashboard is disabled behind {@code VerificationGate}, and the only way through
 * is to read the API container's logs for the URL that was printed there.
 *
 * <p>So registration completes verified when email is off. This is not a weaker
 * check — an unsent verification email proves nothing about the address — it is
 * the removal of a check that was never being performed.</p>
 *
 * <p>The counterpart is {@code AuthIntegrationTest}, which turns email on and
 * asserts the verification journey still gates as it should.</p>
 */
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

        // A token that can never be delivered is a credential sitting in the row
        // for no reason — and a live one, since /verify-email would honour it.
        User stored = userRepository.findByEmail("notoken@example.com").orElseThrow();
        assertThat(stored.getVerificationToken()).isNull();
        assertThat(stored.getVerificationTokenExpiresAt()).isNull();
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

        // VerificationGate keys off this and only this: usePermissions.ts derives
        // emailVerified as `status !== 'PENDING_VERIFICATION'`. So this is the
        // assertion that the out-of-box dashboard is not a wall of disabled
        // buttons — /me carries no emailVerified field of its own.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));
    }
}
