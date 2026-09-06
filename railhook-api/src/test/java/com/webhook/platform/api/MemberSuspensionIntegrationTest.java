package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.CurrentUserResponse;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspending a member, end to end: the owner's request, the row that survives it, and the door
 * that closes behind it.
 *
 * <p>The half a unit test cannot show is that the refusal is structural. Nothing in
 * {@code MemberController} or in any other endpoint asks whether the caller is suspended — the
 * membership stops being a way in at the one place a Membership becomes an authenticated context,
 * which is where a token is minted. So the assertion that matters here is on {@code /auth/login}
 * and {@code /auth/refresh}, not on the member endpoints.</p>
 */
public class MemberSuspensionIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Test1234!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;

    private UUID orgId;
    private String ownerToken;
    private UUID ownerUserId;
    private UUID memberUserId;
    private String memberEmail;

    @BeforeEach
    void seedOrganizationWithAMember() throws Exception {
        String ownerEmail = "suspend-owner-" + UUID.randomUUID() + "@test.com";
        AuthResponse ownerAuth = register(ownerEmail, "SuspendOrg-" + UUID.randomUUID());
        ownerToken = ownerAuth.getAccessToken();

        CurrentUserResponse me = currentUser(ownerToken);
        orgId = me.getOrganization().getId();
        ownerUserId = me.getUser().getId();

        // Seeded directly rather than invited, so this member belongs to exactly one
        // organization: the login assertions below are then about the suspension and not about
        // which of several memberships the token happened to name.
        memberEmail = "suspend-member-" + UUID.randomUUID() + "@test.com";
        User member = User.builder()
                .email(memberEmail)
                .passwordHash(new BCryptPasswordEncoder().encode(PASSWORD))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
        memberUserId = userRepository.saveAndFlush(member).getId();

        membershipRepository.saveAndFlush(Membership.builder()
                .userId(memberUserId)
                .organizationId(orgId)
                .role(MembershipRole.DEVELOPER)
                .status(MembershipStatus.ACTIVE)
                .build());
    }

    @Test
    @DisplayName("an owner suspends a member: the row and the role stay, the way in closes")
    void suspendKeepsTheMemberAndClosesTheDoor() throws Exception {
        login(memberEmail, PASSWORD).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members/" + memberUserId + "/suspend")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));

        Membership row = membershipRepository.findByUserIdAndOrganizationId(memberUserId, orgId).orElseThrow();
        assertEquals(MembershipStatus.DISABLED, row.getStatus());
        assertEquals(MembershipRole.DEVELOPER, row.getRole());

        // Not only at the next login: the access token already in their hands stops working too.
        verify(tokenBlacklistService).revokeAllUserTokens(memberUserId);

        login(memberEmail, PASSWORD).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/orgs/" + orgId + "/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='" + memberEmail + "')].status").value("DISABLED"));
    }

    @Test
    @DisplayName("reinstating gives the member their access back in the role they kept")
    void reinstateRestoresAccess() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members/" + memberUserId + "/suspend")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members/" + memberUserId + "/reinstate")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));

        login(memberEmail, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a member cannot suspend anyone, including the owner")
    void aNonOwnerCannotSuspend() throws Exception {
        MvcResult result = login(memberEmail, PASSWORD).andExpect(status().isOk()).andReturn();
        AuthResponse memberAuth = objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class);

        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members/" + ownerUserId + "/suspend")
                        .header("Authorization", "Bearer " + memberAuth.getAccessToken()))
                .andExpect(status().isForbidden());

        assertEquals(MembershipStatus.ACTIVE,
                membershipRepository.findByUserIdAndOrganizationId(ownerUserId, orgId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("an owner cannot suspend themselves out of their own organization")
    void anOwnerCannotSuspendThemselves() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members/" + ownerUserId + "/suspend")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());

        assertEquals(MembershipStatus.ACTIVE,
                membershipRepository.findByUserIdAndOrganizationId(ownerUserId, orgId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a suspended owner does not count as an owner who could take over")
    void aSuspendedOwnerDoesNotHoldTheLastOwnerSpot() throws Exception {
        String secondOwnerEmail = "suspend-owner2-" + UUID.randomUUID() + "@test.com";
        User secondOwner = userRepository.saveAndFlush(User.builder()
                .email(secondOwnerEmail)
                .passwordHash(new BCryptPasswordEncoder().encode(PASSWORD))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build());
        membershipRepository.saveAndFlush(Membership.builder()
                .userId(secondOwner.getId())
                .organizationId(orgId)
                .role(MembershipRole.OWNER)
                .status(MembershipStatus.ACTIVE)
                .build());

        // Two owners, so suspending one is allowed: one who can still sign in is left.
        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members/" + secondOwner.getId() + "/suspend")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        login(secondOwnerEmail, PASSWORD).andExpect(status().isForbidden());

        // The remaining owner is now the last one who can administer anything, so removing them
        // is refused. Counting owner rows flatly would have allowed it and left an organization
        // whose only owner is suspended — with nobody able to lift the suspension.
        mockMvc.perform(delete("/api/v1/orgs/" + orgId + "/members/" + ownerUserId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());

        assertEquals(MembershipStatus.ACTIVE,
                membershipRepository.findByUserIdAndOrganizationId(ownerUserId, orgId).orElseThrow().getStatus());
    }

    private AuthResponse register(String email, String orgName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email(email)
                                .password(PASSWORD)
                                .organizationName(orgName)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    private CurrentUserResponse currentUser(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), CurrentUserResponse.class);
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest.builder()
                        .email(email)
                        .password(password)
                        .build())));
    }
}
