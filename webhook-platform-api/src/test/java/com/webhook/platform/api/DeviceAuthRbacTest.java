package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.CurrentUserResponse;
import com.webhook.platform.api.dto.DeviceApproveRequest;
import com.webhook.platform.api.dto.DeviceCodeResponse;
import com.webhook.platform.api.dto.DeviceTokenRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.security.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The device-code flow must mint a token whose role comes from the same
 * membership row as the organization the code was approved for, must be single-use
 * under concurrency, and the poll/approve endpoints must be rate-limited.
 *
 * <p>Full stack (real Postgres via {@link AbstractIntegrationTest}) because the
 * concurrency guarantee depends on an actual DB-level compare-and-set (two real
 * transactions racing a conditional UPDATE), which a mocked repository cannot
 * exercise honestly.
 */
@AutoConfigureMockMvc
class DeviceAuthRbacTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private AuthResponse register(String email, String orgName) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName(orgName)
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    private CurrentUserResponse me(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), CurrentUserResponse.class);
    }

    private DeviceCodeResponse initiate() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/device/code"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), DeviceCodeResponse.class);
    }

    @Test
    @DisplayName("a multi-org user approving a device code for the low-privilege org gets that org's role, not their other org's")
    void mintedTokenUsesRoleFromApprovedOrgNotAnArbitraryOne() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        // userA: OWNER of "own org" (via registration).
        AuthResponse ownerAuth = register("consultant@example.com", "Consultant Own Org");
        CurrentUserResponse me = me(ownerAuth.getAccessToken());
        UUID userId = me.getUser().getId();
        UUID ownOrgId = me.getOrganization().getId();
        assertEquals(MembershipRole.OWNER, me.getRole());

        // clientOrg: a separate org (created by a different owner) where userA is
        // granted only VIEWER — the low-privilege membership.
        AuthResponse clientOwnerAuth = register("client-owner@example.com", "Client Org");
        UUID clientOrgId = me(clientOwnerAuth.getAccessToken()).getOrganization().getId();

        Membership viewerMembership = Membership.builder()
                .userId(userId)
                .organizationId(clientOrgId)
                .role(MembershipRole.VIEWER)
                .status(MembershipStatus.ACTIVE)
                .build();
        membershipRepository.save(viewerMembership);

        // Approve the device code while acting in the client org context (a JWT scoped
        // to clientOrgId — exactly what the dashboard would present if the user had the
        // client org selected when they approved the CLI login).
        String clientOrgToken = jwtUtil.generateAccessToken(userId, clientOrgId, MembershipRole.VIEWER, null, true);

        DeviceCodeResponse deviceCode = initiate();
        mockMvc.perform(post("/api/v1/auth/device/approve")
                        .header("Authorization", "Bearer " + clientOrgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceApproveRequest.builder().userCode(deviceCode.getUserCode()).build())))
                .andExpect(status().isOk());

        MvcResult pollResult = mockMvc.perform(post("/api/v1/auth/device/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceTokenRequest.builder().deviceCode(deviceCode.getDeviceCode()).build())))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse minted = objectMapper.readValue(pollResult.getResponse().getContentAsString(), AuthResponse.class);

        assertEquals(clientOrgId, jwtUtil.getOrganizationIdFromToken(minted.getAccessToken()));
        assertEquals(MembershipRole.VIEWER, jwtUtil.getRoleFromToken(minted.getAccessToken()),
                "token minted for the client org must carry VIEWER, not the user's OWNER role from their own org");
        assertTrue(!ownOrgId.equals(clientOrgId), "own org and client org must be distinct for this to be a real test");
    }

    @Test
    @DisplayName("a user with no membership in the approved org is refused, not granted a token")
    void refusedWhenNoMembershipInApprovedOrg() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        AuthResponse ownerAuth = register("lone-owner@example.com", "Lone Org");
        UUID userId = me(ownerAuth.getAccessToken()).getUser().getId();

        // A second, unrelated org that userId has never been added to.
        AuthResponse otherOwnerAuth = register("other-owner@example.com", "Other Org");
        UUID otherOrgId = me(otherOwnerAuth.getAccessToken()).getOrganization().getId();

        // Forge a token claiming membership in otherOrgId — the real-world equivalent is
        // a membership revoked between JWT issuance and the poll. The service must not
        // trust the org/role encoded on the JWT to imply real membership; it must fail
        // closed.
        String bogusOrgToken = jwtUtil.generateAccessToken(userId, otherOrgId, MembershipRole.OWNER, null, true);

        DeviceCodeResponse deviceCode = initiate();
        mockMvc.perform(post("/api/v1/auth/device/approve")
                        .header("Authorization", "Bearer " + bogusOrgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceApproveRequest.builder().userCode(deviceCode.getUserCode()).build())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/device/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceTokenRequest.builder().deviceCode(deviceCode.getDeviceCode()).build())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("polling an already-consumed code fails")
    void pollingAlreadyConsumedCodeFails() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        AuthResponse ownerAuth = register("single-use@example.com", "Single Use Org");
        String accessToken = ownerAuth.getAccessToken();

        DeviceCodeResponse deviceCode = initiate();
        mockMvc.perform(post("/api/v1/auth/device/approve")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceApproveRequest.builder().userCode(deviceCode.getUserCode()).build())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/device/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceTokenRequest.builder().deviceCode(deviceCode.getDeviceCode()).build())))
                .andExpect(status().isOk());

        // Second poll of the same (now CONSUMED) device_code must not mint another pair.
        mockMvc.perform(post("/api/v1/auth/device/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceTokenRequest.builder().deviceCode(deviceCode.getDeviceCode()).build())))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("two concurrent polls of one approved code yield exactly one token pair")
    void concurrentPollsYieldExactlyOneTokenPair() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);

        AuthResponse ownerAuth = register("racer@example.com", "Racer Org");
        String accessToken = ownerAuth.getAccessToken();

        DeviceCodeResponse deviceCode = initiate();
        mockMvc.perform(post("/api/v1/auth/device/approve")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceApproveRequest.builder().userCode(deviceCode.getUserCode()).build())))
                .andExpect(status().isOk());

        String tokenBody = objectMapper.writeValueAsString(
                DeviceTokenRequest.builder().deviceCode(deviceCode.getDeviceCode()).build());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        Callable<Integer> poll = () -> {
            startLatch.await();
            return mockMvc.perform(post("/api/v1/auth/device/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tokenBody))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };

        try {
            Future<Integer> f1 = executor.submit(poll);
            Future<Integer> f2 = executor.submit(poll);
            startLatch.countDown();

            int status1 = f1.get(30, TimeUnit.SECONDS);
            int status2 = f2.get(30, TimeUnit.SECONDS);

            AtomicInteger successCount = new AtomicInteger(0);
            for (int s : List.of(status1, status2)) {
                if (s == 200) {
                    successCount.incrementAndGet();
                } else {
                    assertTrue(s == 410 || s == 409,
                            "losing poll must fail closed (410/409), got " + s);
                }
            }
            assertEquals(1, successCount.get(), "exactly one of the two concurrent polls must win");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("the poll endpoint rate-limits")
    void pollEndpointRateLimits() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/device/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceTokenRequest.builder().deviceCode("whatever-code").build())))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("the approve (verification) endpoint rate-limits")
    void approveEndpointRateLimits() throws Exception {
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(false);

        AuthResponse ownerAuth = register("rate-limited-approver@example.com", "RL Org");

        mockMvc.perform(post("/api/v1/auth/device/approve")
                        .header("Authorization", "Bearer " + ownerAuth.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DeviceApproveRequest.builder().userCode("WHAT-EVER").build())))
                .andExpect(status().isTooManyRequests());
    }
}
