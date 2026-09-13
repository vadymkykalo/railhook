package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.AuditLog;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who reaches the platform-admin panel with an ordinary sign-in.
 *
 * <p>The panel is for the people running this deployment, named by address in
 * {@code PLATFORM_ADMIN_EMAILS}. Being listed is not enough on its own: the address has to be
 * verified — otherwise anyone could register the operator's address on a deployment that does
 * not send mail and walk in — the account has to be active, and the sign-in behind the token has
 * to be recent, so a laptop left signed in for a week is not a standing key to every tenant.
 *
 * <p>And an organization OWNER, however privileged inside their own tenant, gets nothing.
 */
@TestPropertySource(properties = {
        "platform.admin.emails= Operator-One@example.com , ,operator-two@example.com"
})
public class PlatformAdminAccessRbacTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "operator-one@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Account(String token, UUID userId, UUID organizationId) {
    }

    /**
     * A fresh sign-in for {@code email}: registered the first time, signed in again after that.
     * The listed addresses are fixed by the class's properties while the context — and so the
     * database — is shared by every test, so each test after the first finds the account there,
     * possibly left disabled or unverified by another; it is put back to active and verified first.
     */
    private Account register(String email) throws Exception {
        MvcResult result;
        User existing = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (existing == null) {
            result = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                    .email(email)
                                    .password("Test1234!")
                                    .organizationName("Org of " + email)
                                    .build())))
                    .andExpect(status().isCreated())
                    .andReturn();
        } else {
            existing.setStatus(UserStatus.ACTIVE);
            existing.setEmailVerified(true);
            userRepository.save(existing);
            result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"Test1234!\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
        }
        String token = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        MvcResult orgs = mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        UUID organizationId = UUID.fromString(
                objectMapper.readTree(orgs.getResponse().getContentAsString()).get(0).get("id").asText());
        return new Account(token, user.getId(), organizationId);
    }

    private Account registerVerified(String email) throws Exception {
        Account account = register(email);
        User user = userRepository.findById(account.userId()).orElseThrow();
        user.setEmailVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        return account;
    }

    private List<RequestBuilder> everyAdminEndpoint(UUID subject, String bearer) {
        String auth = "Bearer " + bearer;
        String org = "/api/v1/admin/organizations/" + subject;
        return List.of(
                get("/api/v1/admin/overview").header("Authorization", auth),
                get("/api/v1/admin/organizations").header("Authorization", auth),
                get(org).header("Authorization", auth),
                get(org + "/usage").header("Authorization", auth),
                get(org + "/members").header("Authorization", auth),
                get(org + "/projects").header("Authorization", auth),
                get(org + "/audit-log").header("Authorization", auth),
                get("/api/v1/admin/users").header("Authorization", auth),
                post(org + "/suspend").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"trying it on\"}"),
                post(org + "/reinstate").header("Authorization", auth),
                post("/api/v1/admin/encryption/rotate").header("Authorization", auth));
    }

    // ── Refused ────────────────────────────────────────────────────

    @Test
    public void anOwnerWhoIsNotListedIsRefusedEverywhere() throws Exception {
        Account owner = registerVerified("plain-owner@example.com");

        for (RequestBuilder request : everyAdminEndpoint(owner.organizationId(), owner.token())) {
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(false));
    }

    @Test
    public void aListedAddressThatIsNotVerifiedIsRefused() throws Exception {
        Account impostor = register("operator-two@example.com");
        User user = userRepository.findById(impostor.userId()).orElseThrow();
        user.setEmailVerified(false);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        userRepository.save(user);

        for (RequestBuilder request : everyAdminEndpoint(impostor.organizationId(), impostor.token())) {
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + impostor.token()))
                .andExpect(jsonPath("$.platformAdmin").value(false));
    }

    @Test
    public void aListedAccountThatIsDisabledIsRefused() throws Exception {
        Account admin = registerVerified(ADMIN_EMAIL);
        User user = userRepository.findById(admin.userId()).orElseThrow();
        user.setStatus(UserStatus.DISABLED);
        userRepository.save(user);

        for (RequestBuilder request : everyAdminEndpoint(admin.organizationId(), admin.token())) {
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
    }

    @Test
    public void aSignInOlderThanTwelveHoursMustBeRepeated() throws Exception {
        Account admin = registerVerified(ADMIN_EMAIL);
        jdbcTemplate.update("UPDATE user_sessions SET created_at = now() - interval '13 hours' WHERE user_id = ?",
                admin.userId());

        mockMvc.perform(get("/api/v1/admin/overview").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("reauthentication_required"));
    }

    @Test
    public void anAdminOverTheRateLimitIsSlowedDown() throws Exception {
        Account admin = registerVerified(ADMIN_EMAIL);
        when(authRateLimiterService.allowPlatformAdmin(anyString())).thenReturn(false);

        mockMvc.perform(get("/api/v1/admin/overview").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    public void aListedAdminCannotRotateEncryptionKeysWithASignIn() throws Exception {
        // Re-encrypting every tenant's secrets stays on the operator token: it is an infrastructure
        // act run from the deployment, not something a browser session should be one click from.
        Account admin = registerVerified(ADMIN_EMAIL);

        mockMvc.perform(post("/api/v1/admin/encryption/rotate")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isForbidden());
    }

    // ── Admitted ───────────────────────────────────────────────────

    @Test
    public void aVerifiedActiveListedAdminSeesThePanel() throws Exception {
        Account admin = registerVerified(ADMIN_EMAIL);
        Account tenant = registerVerified("some-customer@example.com");
        String auth = "Bearer " + admin.token();
        String org = "/api/v1/admin/organizations/" + tenant.organizationId();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", auth))
                .andExpect(jsonPath("$.platformAdmin").value(true));

        mockMvc.perform(get("/api/v1/admin/overview").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations").isNumber())
                .andExpect(jsonPath("$.users").isNumber())
                .andExpect(jsonPath("$.signupsToday").isNumber())
                .andExpect(jsonPath("$.deliveriesFailed24h").isNumber())
                .andExpect(jsonPath("$.organizationsNearQuota").isNumber())
                .andExpect(jsonPath("$.recentSignups").isArray());

        mockMvc.perform(get("/api/v1/admin/organizations")
                        .param("search", "some-customer@example.com")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(tenant.organizationId().toString()))
                .andExpect(jsonPath("$.content[0].ownerEmail").value("some-customer@example.com"))
                .andExpect(jsonPath("$.content[0].eventsThisMonth").isNumber())
                .andExpect(jsonPath("$.content[0].eventsLimit").isNumber());

        mockMvc.perform(get(org + "/members").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("some-customer@example.com"))
                .andExpect(jsonPath("$.content[0].role").value("OWNER"))
                .andExpect(jsonPath("$.content[0].signInMethods[0]").value("PASSWORD"));

        mockMvc.perform(get(org + "/projects").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get(org + "/audit-log").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get(org + "/usage").header("Authorization", auth))
                .andExpect(status().isOk());

        MvcResult users = mockMvc.perform(get("/api/v1/admin/users")
                        .param("search", "some-customer")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("some-customer@example.com"))
                .andExpect(jsonPath("$.content[0].emailVerified").value(true))
                .andExpect(jsonPath("$.content[0].organizations[0].role").value("OWNER"))
                .andReturn();
        assertNoSecrets(users.getResponse().getContentAsString());
    }

    @Test
    public void anAdminSuspensionIsSignedWithTheirOwnAddressAndAudited() throws Exception {
        Account admin = registerVerified(ADMIN_EMAIL);
        Account tenant = registerVerified("to-be-suspended@example.com");

        mockMvc.perform(post("/api/v1/admin/organizations/" + tenant.organizationId() + "/suspend")
                        .header("Authorization", "Bearer " + admin.token())
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"phishing endpoints\",\"suspendedBy\":\"someone-else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspendedBy").value(ADMIN_EMAIL));

        assertThat(organizationRepository.findById(tenant.organizationId()).orElseThrow().getSuspendedBy())
                .isEqualTo(ADMIN_EMAIL);

        // The write is recorded against the organization acted on — where its owners will look —
        // and not against the admin's own organization, which had nothing to do with it.
        List<AuditLog> written = awaitAudit(() -> TenantContext.callAsSystem(() -> auditLogRepository.findAll()
                .stream()
                .filter(a -> admin.userId().equals(a.getUserId()))
                .toList()), 2);

        assertThat(written).anySatisfy(row -> {
            assertThat(row.getAction()).isEqualTo("ORGANIZATION_SUSPENDED");
            assertThat(row.getOrganizationId()).isEqualTo(tenant.organizationId());
        });
        assertThat(written).anySatisfy(row -> {
            assertThat(row.getAction()).isEqualTo("PLATFORM_ADMIN_ACCESS");
            assertThat(row.getOrganizationId()).isEqualTo(TenantContext.SYSTEM);
            assertThat(row.getResourceId()).isEqualTo(tenant.organizationId());
            assertThat(row.getClientIp()).isNotBlank();
        });
        assertThat(written).noneSatisfy(row ->
                assertThat(row.getOrganizationId()).isEqualTo(admin.organizationId()));
    }

    @Test
    public void everyAdminReadIsAudited() throws Exception {
        Account admin = registerVerified(ADMIN_EMAIL);

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk());

        // The account is shared with the other tests in this class, so it has other rows too.
        List<AuditLog> reads = awaitAudit(() -> TenantContext.callAsSystem(() -> auditLogRepository.findAll()
                .stream()
                .filter(a -> "PLATFORM_ADMIN_ACCESS".equals(a.getAction()))
                .filter(a -> admin.userId().equals(a.getUserId()))
                .filter(a -> a.getDetails() != null && a.getDetails().contains("\"/api/v1/admin/users\""))
                .toList()), 1);
        JsonNode details = objectMapper.readTree(reads.get(0).getDetails());
        assertThat(details.get("method").asText()).isEqualTo("GET");
        assertThat(details.get("path").asText()).isEqualTo("/api/v1/admin/users");
        assertThat(details.get("credential").asText()).isEqualTo("session");
        assertThat(reads.get(0).getOrganizationId()).isEqualTo(TenantContext.SYSTEM);
        assertThat(reads.get(0).getClientIp()).isNotBlank();
    }

    @Test
    public void theOperatorTokenStillWorks() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview").header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/users").header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk());
    }

    private static void assertNoSecrets(String json) {
        assertThat(json).doesNotContain("passwordHash", "verificationToken", "passwordResetToken",
                "refreshTokenJti", "Test1234!");
    }

    private static <T> List<T> awaitAudit(java.util.function.Supplier<List<T>> query, int atLeast)
            throws InterruptedException {
        List<T> rows = query.get();
        for (int i = 0; i < 50 && rows.size() < atLeast; i++) {
            Thread.sleep(100);
            rows = query.get();
        }
        assertThat(rows).hasSizeGreaterThanOrEqualTo(atLeast);
        return rows;
    }
}
