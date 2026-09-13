package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.domain.entity.AuditLog;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.ChangeEmailRequest;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.service.EmailService;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing the address an account signs in with, end to end.
 *
 * <p>It started with one person registered as {@code wheelet1228@gmail.con}, stuck: the product had
 * no way to change an address at all. The owner's worry is the other direction — that a way to
 * change it becomes a way around the checks the address stands for. So most of what is asserted
 * here is what does <em>not</em> happen: an unverified account stays unverified, a verified one
 * keeps its address until the new one is proved, nothing reveals who else has an account, the mail
 * it can cause is capped, the organization and its quota are untouched, and every step is in the
 * audit log its owner reads.
 */
class EmailChangeIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Test1234!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmailService emailService;
    @MockitoBean private CaptchaVerifier captchaVerifier;

    private record Account(String email, String accessToken, String refreshToken, UUID userId, UUID organizationId) {
    }

    @BeforeEach
    void mailAndChallengeWork() {
        when(emailService.isEnabled()).thenReturn(true);
        when(captchaVerifier.isEnabled()).thenReturn(true);
        when(captchaVerifier.verify(any(), any())).thenReturn(true);
    }

    @Nested
    class UnverifiedAccount {

        @Test
        void movesToTheNewAddressAtOnceAndStaysUnverified() throws Exception {
            Account account = register("wheelet1228@gmail.con.example");
            String oldToken = lastVerificationToken(account.email());

            change(account, ChangeEmailRequest.builder().newEmail("wheelet1228@example.com").captchaToken("ok").build())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.applied").value(true))
                    .andExpect(jsonPath("$.email").value("wheelet1228@example.com"))
                    .andExpect(jsonPath("$.pendingEmail").doesNotExist());

            // Still restricted: moving the address proves nothing about it.
            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(account)))
                    .andExpect(jsonPath("$.user.email").value("wheelet1228@example.com"))
                    .andExpect(jsonPath("$.user.status").value("PENDING_VERIFICATION"));

            mockMvc.perform(post("/api/v1/auth/verify-email").param("token", oldToken))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/v1/auth/verify-email").param("token", lastVerificationToken("wheelet1228@example.com")))
                    .andExpect(status().isOk());

            assertThat(auditRows(account.userId(), AuditAction.EMAIL_CHANGED)).singleElement().satisfies(row -> {
                assertThat(row.getOrganizationId()).isEqualTo(account.organizationId());
                assertThat(row.getDetails()).contains("wheelet1228@example.com");
            });
        }

        @Test
        void answersTheSameChallengeRegistrationDoes() throws Exception {
            Account account = register("captcha@example.com");
            when(captchaVerifier.verify(any(), any())).thenReturn(false);
            clearInvocations(emailService);

            change(account, ChangeEmailRequest.builder().newEmail("bot-target@example.com").build())
                    .andExpect(status().isBadRequest());

            assertThat(userRepository.findById(account.userId()).orElseThrow().getEmail()).isEqualTo("captcha@example.com");
            verify(emailService, never()).sendVerificationEmail(eq("bot-target@example.com"), anyString());
        }

        @Test
        void refusesATakenAddressExactlyAsRegistrationDoes() throws Exception {
            register("someone-else@example.com");
            Account account = register("taker@example.com");
            clearInvocations(emailService);

            change(account, ChangeEmailRequest.builder().newEmail("someone-else@example.com").captchaToken("ok").build())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Email already exists"));

            verify(emailService, never()).sendVerificationEmail(eq("someone-else@example.com"), anyString());
        }

        @Test
        void changesItsAddressAtMostThreeTimesADay() throws Exception {
            Account account = register("hops@example.com");
            for (int i = 1; i <= 3; i++) {
                change(account, ChangeEmailRequest.builder().newEmail("hop" + i + "@example.com").captchaToken("ok").build())
                        .andExpect(status().isOk());
            }

            change(account, ChangeEmailRequest.builder().newEmail("hop4@example.com").captchaToken("ok").build())
                    .andExpect(status().isTooManyRequests());

            assertThat(userRepository.findById(account.userId()).orElseThrow().getEmail()).isEqualTo("hop3@example.com");
            assertThat(auditRows(account.userId(), AuditAction.EMAIL_RATE_LIMITED)).isNotEmpty();
        }

        @Test
        void asksAnAddressToProveItselfAtMostFiveTimesADayAcrossResendAndChange() throws Exception {
            Account account = register("sends@example.com"); // 1
            for (int i = 0; i < 4; i++) {                     // 2..5
                mockMvc.perform(post("/api/v1/auth/resend-verification").param("email", "sends@example.com"))
                        .andExpect(status().isOk());
            }

            mockMvc.perform(post("/api/v1/auth/resend-verification").param("email", "sends@example.com"))
                    .andExpect(status().isTooManyRequests());
            change(account, ChangeEmailRequest.builder().newEmail("sends-2@example.com").captchaToken("ok").build())
                    .andExpect(status().isTooManyRequests());

            assertThat(userRepository.findById(account.userId()).orElseThrow().getEmail()).isEqualTo("sends@example.com");
            assertThat(auditRows(account.userId(), AuditAction.EMAIL_RATE_LIMITED)).isNotEmpty();
        }

        @Test
        void isRateLimitedPerAddressLikeResend() throws Exception {
            Account account = register("per-ip@example.com");
            when(authRateLimiterService.allowLogin(anyString(), any())).thenReturn(false);

            change(account, ChangeEmailRequest.builder().newEmail("per-ip-2@example.com").captchaToken("ok").build())
                    .andExpect(status().isTooManyRequests());

            assertThat(auditRows(account.userId(), AuditAction.EMAIL_RATE_LIMITED)).isNotEmpty();
        }

        @Test
        void keepsTheOrganizationItsPlanAndItsUsage() throws Exception {
            Account account = register("quota@example.com");
            long organizations = countOrganizations();
            UUID planBefore = planOf(account.organizationId());

            change(account, ChangeEmailRequest.builder().newEmail("quota-2@example.com").captchaToken("ok").build())
                    .andExpect(status().isOk());

            assertThat(countOrganizations()).isEqualTo(organizations);
            assertThat(membershipRepository.findByUserId(account.userId()))
                    .singleElement()
                    .satisfies(m -> assertThat(m.getOrganizationId()).isEqualTo(account.organizationId()));
            assertThat(planOf(account.organizationId())).isEqualTo(planBefore);
        }
    }

    @Nested
    class VerifiedAccount {

        @Test
        void keepsItsAddressUntilTheNewOneIsConfirmedThenSignsEverythingOut() throws Exception {
            Account account = verified("owner@example.com");

            change(account, ChangeEmailRequest.builder().newEmail("owner-new@example.com").currentPassword(PASSWORD).build())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.applied").value(false))
                    .andExpect(jsonPath("$.email").value("owner@example.com"))
                    .andExpect(jsonPath("$.pendingEmail").value("owner-new@example.com"))
                    .andExpect(jsonPath("$.pendingExpiresAt").exists());

            assertThat(userRepository.findById(account.userId()).orElseThrow().getEmail()).isEqualTo("owner@example.com");
            mockMvc.perform(get("/api/v1/auth/email-change").header("Authorization", bearer(account)))
                    .andExpect(jsonPath("$.pendingEmail").value("owner-new@example.com"));
            String confirm = confirmationToken("owner-new@example.com");
            noticeToken("owner@example.com");

            mockMvc.perform(post("/api/v1/auth/email-change/confirm").param("token", confirm))
                    .andExpect(status().isOk());

            User user = userRepository.findById(account.userId()).orElseThrow();
            assertThat(user.getEmail()).isEqualTo("owner-new@example.com");
            assertThat(user.getEmailVerified()).isTrue();
            verify(tokenBlacklistService, atLeastOnce()).revokeAllUserTokens(account.userId());
            assertThat(jdbc.queryForObject(
                    "select count(*) from user_sessions where user_id = ? and revoked_at is null",
                    Long.class, account.userId())).isZero();

            // Single use.
            mockMvc.perform(post("/api/v1/auth/email-change/confirm").param("token", confirm))
                    .andExpect(status().isBadRequest());

            assertThat(auditRows(account.userId(), AuditAction.EMAIL_CHANGE_REQUESTED)).isNotEmpty();
            assertThat(auditRows(account.userId(), AuditAction.EMAIL_CHANGED)).isNotEmpty();
        }

        @Test
        void showsThePendingChangeToTheOrganizationOwnerInTheAuditLog() throws Exception {
            Account account = verified("visible@example.com");
            change(account, ChangeEmailRequest.builder().newEmail("visible-new@example.com").currentPassword(PASSWORD).build())
                    .andExpect(status().isOk());
            auditRows(account.userId(), AuditAction.EMAIL_CHANGE_REQUESTED);

            mockMvc.perform(get("/api/v1/audit-log").param("action", "EMAIL_CHANGE_REQUESTED")
                            .header("Authorization", bearer(account)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].action").value("EMAIL_CHANGE_REQUESTED"))
                    .andExpect(jsonPath("$.content[0].details").value(org.hamcrest.Matchers.containsString("visible-new@example.com")));
        }

        @Test
        void needsThePasswordReEntered() throws Exception {
            Account account = verified("pw@example.com");
            clearInvocations(emailService);

            change(account, ChangeEmailRequest.builder().newEmail("pw-new@example.com").currentPassword("wrong-Password1!").build())
                    .andExpect(status().isBadRequest());
            change(account, ChangeEmailRequest.builder().newEmail("pw-new@example.com").build())
                    .andExpect(status().isBadRequest());

            verify(emailService, never()).sendEmailChangeConfirmation(anyString(), anyString());
        }

        @Test
        void refusesATakenAddressAndSendsItNothing() throws Exception {
            register("occupied@example.com");
            Account account = verified("mover@example.com");
            clearInvocations(emailService);

            change(account, ChangeEmailRequest.builder().newEmail("occupied@example.com").currentPassword(PASSWORD).build())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Email already exists"));

            verify(emailService, never()).sendEmailChangeConfirmation(eq("occupied@example.com"), anyString());
            verify(emailService, never()).sendEmailChangeNotice(anyString(), anyString(), anyString());
        }

        @Test
        void refusesAnAddressThatWasTakenWhileTheChangeWaited() throws Exception {
            Account account = verified("slow@example.com");
            change(account, ChangeEmailRequest.builder().newEmail("contested@example.com").currentPassword(PASSWORD).build())
                    .andExpect(status().isOk());
            String confirm = confirmationToken("contested@example.com");
            register("contested@example.com");

            mockMvc.perform(post("/api/v1/auth/email-change/confirm").param("token", confirm))
                    .andExpect(status().isConflict());

            assertThat(userRepository.findById(account.userId()).orElseThrow().getEmail()).isEqualTo("slow@example.com");
        }

        @Test
        void cancelledFromSettingsTheLinkNoLongerWorks() throws Exception {
            Account account = verified("cancel@example.com");
            change(account, ChangeEmailRequest.builder().newEmail("cancel-new@example.com").currentPassword(PASSWORD).build())
                    .andExpect(status().isOk());
            String confirm = confirmationToken("cancel-new@example.com");

            mockMvc.perform(delete("/api/v1/auth/email-change").header("Authorization", bearer(account)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/auth/email-change").header("Authorization", bearer(account)))
                    .andExpect(jsonPath("$.pendingEmail").doesNotExist());
            mockMvc.perform(post("/api/v1/auth/email-change/confirm").param("token", confirm))
                    .andExpect(status().isBadRequest());
            assertThat(userRepository.findById(account.userId()).orElseThrow().getEmail()).isEqualTo("cancel@example.com");
            assertThat(auditRows(account.userId(), AuditAction.EMAIL_CHANGE_CANCELLED)).isNotEmpty();
        }

        @Test
        void thisWasntMeCancelsAndSignsEveryoneOut() throws Exception {
            Account account = verified("victim@example.com");
            change(account, ChangeEmailRequest.builder().newEmail("attacker@example.com").currentPassword(PASSWORD).build())
                    .andExpect(status().isOk());
            String confirm = confirmationToken("attacker@example.com");
            String cancel = noticeToken("victim@example.com");
            clearInvocations(tokenBlacklistService);

            mockMvc.perform(post("/api/v1/auth/email-change/cancel").param("token", cancel))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/auth/email-change/confirm").param("token", confirm))
                    .andExpect(status().isBadRequest());
            verify(tokenBlacklistService, atLeastOnce()).revokeAllUserTokens(account.userId());
            assertThat(userRepository.findById(account.userId()).orElseThrow().getEmail()).isEqualTo("victim@example.com");
            assertThat(auditRows(account.userId(), AuditAction.EMAIL_CHANGE_CANCELLED)).isNotEmpty();
            mockMvc.perform(post("/api/v1/auth/email-change/cancel").param("token", cancel))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anExpiredConfirmationChangesNothing() throws Exception {
            Account account = verified("late@example.com");
            change(account, ChangeEmailRequest.builder().newEmail("late-new@example.com").currentPassword(PASSWORD).build())
                    .andExpect(status().isOk());
            String confirm = confirmationToken("late-new@example.com");
            jdbc.update("update email_change_requests set expires_at = now() - interval '1 minute' where user_id = ?",
                    account.userId());

            mockMvc.perform(post("/api/v1/auth/email-change/confirm").param("token", confirm))
                    .andExpect(status().isBadRequest());

            assertThat(userRepository.findById(account.userId()).orElseThrow().getEmail()).isEqualTo("late@example.com");
        }

        @Test
        void resendGoesToThePendingAddressAndCountsTowardsTheCap() throws Exception {
            Account account = verified("resend@example.com"); // register: 1 send
            change(account, ChangeEmailRequest.builder().newEmail("resend-new@example.com").currentPassword(PASSWORD).build())
                    .andExpect(status().isOk());                  // 2
            for (int i = 0; i < 3; i++) {                         // 3..5
                mockMvc.perform(post("/api/v1/auth/email-change/resend").header("Authorization", bearer(account)))
                        .andExpect(status().isOk());
            }

            mockMvc.perform(post("/api/v1/auth/email-change/resend").header("Authorization", bearer(account)))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        void aGoogleOnlyAccountConfirmsThroughARecentSignIn() throws Exception {
            Account account = verified("google-only@example.com");
            jdbc.update("update users set password_hash = null where id = ?", account.userId());

            change(account, ChangeEmailRequest.builder().newEmail("google-new@example.com").build())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pendingEmail").value("google-new@example.com"));

            jdbc.update("update user_sessions set created_at = now() - interval '11 minutes' where user_id = ?",
                    account.userId());
            change(account, ChangeEmailRequest.builder().newEmail("google-newer@example.com").build())
                    .andExpect(status().isForbidden());
        }
    }

    private ResultActions change(Account account, ChangeEmailRequest request) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/email-change")
                .header("Authorization", bearer(account))
                .cookie(new Cookie("refresh_token", account.refreshToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private Account register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email(email).password(PASSWORD).organizationName("Org " + email).captchaToken("ok")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        return account(email, result);
    }

    /** Registered, verified, and signed in again so the session's token says so. */
    private Account verified(String email) throws Exception {
        register(email);
        mockMvc.perform(post("/api/v1/auth/verify-email").param("token", lastVerificationToken(email)))
                .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(LoginRequest.builder().email(email).password(PASSWORD).build())))
                .andExpect(status().isOk())
                .andReturn();
        return account(email, login);
    }

    private Account account(String email, MvcResult result) throws Exception {
        AuthResponse auth = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        User user = userRepository.findByEmail(email).orElseThrow();
        UUID organizationId = membershipRepository.findByUserId(user.getId()).get(0).getOrganizationId();
        return new Account(email, auth.getAccessToken(), result.getResponse().getCookie("refresh_token").getValue(),
                user.getId(), organizationId);
    }

    private String lastVerificationToken(String email) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendVerificationEmail(eq(email), token.capture());
        return token.getValue();
    }

    private String confirmationToken(String newEmail) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendEmailChangeConfirmation(eq(newEmail), token.capture());
        return token.getValue();
    }

    private String noticeToken(String oldEmail) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendEmailChangeNotice(eq(oldEmail), anyString(), token.capture());
        return token.getValue();
    }

    private static String bearer(Account account) {
        return "Bearer " + account.accessToken();
    }

    private long countOrganizations() {
        return jdbc.queryForObject("select count(*) from organizations", Long.class);
    }

    private UUID planOf(UUID organizationId) {
        return jdbc.queryForObject("select plan_id from organizations where id = ?", UUID.class, organizationId);
    }

    /** Audit rows are written off the request thread, so give the writer a moment. */
    private List<AuditLog> auditRows(UUID userId, AuditAction action) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        List<AuditLog> rows;
        do {
            rows = auditLogRepository.findAll().stream()
                    .filter(row -> userId.equals(row.getUserId()) && action.name().equals(row.getAction()))
                    .toList();
            if (!rows.isEmpty()) {
                return rows;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        return rows;
    }
}
