package com.webhook.platform.api;

import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.service.SuspensionLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator back-office, and the suspension it exists to apply.
 *
 * <p>Two things are being pinned here, and the second is the one that was missing entirely.
 *
 * <p>Who may reach it: {@code /api/v1/admin/**} takes the platform-admin operator credential and
 * nothing else. An organization OWNER — the most privileged tenant role there is — must not be
 * able to list other people's organizations or suspend anybody, because the credential is the
 * deployment's, not a tenant's.
 *
 * <p>What suspension does: {@code BillingStatus.SUSPENDED} was written by the dunning scheduler
 * and read by nothing, so a suspended organization went on ingesting and delivering exactly as
 * before, and an operator had no way to suspend anyone except by editing the database. Writes
 * are refused with the operator's stated reason; reads keep working, so the tenant can sign in
 * and be told what happened rather than watching the dashboard fail.
 */
@AutoConfigureMockMvc
public class OrganizationSuspensionRbacTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private SuspensionLookup suspensionLookup;

    private record Tenant(String token, UUID organizationId) {
    }

    private Tenant registerTenant(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email(email)
                                .password("Test1234!")
                                .organizationName("Suspension Co")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();

        MvcResult orgs = mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        UUID organizationId = UUID.fromString(
                objectMapper.readTree(orgs.getResponse().getContentAsString()).get(0).get("id").asText());

        return new Tenant(token, organizationId);
    }

    // ── Who may reach the back-office ──────────────────────────────

    @Test
    public void anOwnerCannotListEveryOrganization() throws Exception {
        Tenant tenant = registerTenant("suspension-owner@example.com");

        mockMvc.perform(get("/api/v1/admin/organizations")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void anOwnerCannotSuspendAnybody() throws Exception {
        Tenant tenant = registerTenant("suspension-nonadmin@example.com");

        mockMvc.perform(post("/api/v1/admin/organizations/" + tenant.organizationId() + "/suspend")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"trying it on\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void theOperatorSeesEveryOrganization() throws Exception {
        registerTenant("suspension-listed@example.com");

        mockMvc.perform(get("/api/v1/admin/organizations")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].planName").exists());
    }

    @Test
    public void aSuspensionWithoutAReasonIsRefused() throws Exception {
        Tenant tenant = registerTenant("suspension-noreason@example.com");

        // The tenant is shown the reason, so there has to be one.
        mockMvc.perform(post("/api/v1/admin/organizations/" + tenant.organizationId() + "/suspend")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    // ── What suspension does ───────────────────────────────────────

    @Test
    public void aSuspendedOrganizationCannotWriteAndIsToldWhy() throws Exception {
        Tenant tenant = registerTenant("suspension-blocked@example.com");

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Before\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/organizations/" + tenant.organizationId() + "/suspend")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam reports\",\"suspendedBy\":\"ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspendedAt").exists())
                .andExpect(jsonPath("$.suspensionReason").value("spam reports"));

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"After\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("spam reports")));
    }

    @Test
    public void aSuspendedOrganizationCanStillRead() throws Exception {
        Tenant tenant = registerTenant("suspension-can-read@example.com");

        mockMvc.perform(post("/api/v1/admin/organizations/" + tenant.organizationId() + "/suspend")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"under review\"}"))
                .andExpect(status().isOk());

        // Otherwise the tenant cannot sign in to find out what happened, and support cannot look
        // at the same screens they can.
        mockMvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());
    }

    @Test
    public void reinstatingRestoresWrites() throws Exception {
        Tenant tenant = registerTenant("suspension-reinstated@example.com");

        mockMvc.perform(post("/api/v1/admin/organizations/" + tenant.organizationId() + "/suspend")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"mistake\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/organizations/" + tenant.organizationId() + "/reinstate")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspendedAt").doesNotExist());

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"After reinstating\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    public void suspensionDoesNotTouchTheBillingStatus() throws Exception {
        // The two are separate on purpose: billingStatus belongs to the payment state machine,
        // and an abuse suspension recorded there would be lifted by the next successful charge.
        Tenant tenant = registerTenant("suspension-billing@example.com");

        mockMvc.perform(post("/api/v1/admin/organizations/" + tenant.organizationId() + "/suspend")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"abuse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingStatus").value("ACTIVE"));

        assertThat(organizationRepository.findById(tenant.organizationId()).orElseThrow().isSuspended())
                .isTrue();
        assertThat(suspensionLookup.forOrganization(tenant.organizationId())).isPresent();
    }

    // ── What the operator can see about one tenant ─────────────────

    @Test
    public void theOperatorSeesWhatATenantHasUsedAgainstTheirPlan() throws Exception {
        Tenant tenant = registerTenant("suspension-usage@example.com");

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Counted\"}"))
                .andExpect(status().isCreated());

        // "Are they near their limit" is the question behind most support tickets that reach an
        // operator, and answering it used to mean a psql session against the tenant's rows.
        mockMvc.perform(get("/api/v1/admin/organizations/" + tenant.organizationId() + "/usage")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.current").value(1))
                .andExpect(jsonPath("$.events.limit").exists())
                .andExpect(jsonPath("$.periodStart").exists());
    }

    @Test
    public void usageIsCountedForTheTenantAskedAboutAndNotTheAsker() throws Exception {
        Tenant busy = registerTenant("suspension-usage-busy@example.com");
        Tenant quiet = registerTenant("suspension-usage-quiet@example.com");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/projects")
                            .header("Authorization", "Bearer " + busy.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Busy " + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        // The operator holds no organization of their own, so this only works by entering the
        // subject's scope. Getting that wrong reads as an empty tenant rather than an error.
        mockMvc.perform(get("/api/v1/admin/organizations/" + busy.organizationId() + "/usage")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.current").value(3));

        mockMvc.perform(get("/api/v1/admin/organizations/" + quiet.organizationId() + "/usage")
                        .header("X-Platform-Admin-Token", PLATFORM_ADMIN_TEST_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.current").value(0));
    }

    @Test
    public void anOwnerCannotReadAnotherTenantsUsage() throws Exception {
        Tenant mine = registerTenant("suspension-usage-mine@example.com");

        mockMvc.perform(get("/api/v1/admin/organizations/" + mine.organizationId() + "/usage")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isForbidden());
    }
}
