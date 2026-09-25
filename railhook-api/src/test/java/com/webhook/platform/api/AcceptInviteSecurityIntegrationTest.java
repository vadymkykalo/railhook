package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import com.webhook.platform.common.util.CryptoUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
public class AcceptInviteSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MembershipRepository membershipRepository;

    private record UserContext(AuthResponse auth, CurrentUserResponse currentUser) {}

    private UserContext registerUser(String email, String orgName) throws Exception {
        RegisterRequest req = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName(orgName)
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(
                regResult.getResponse().getContentAsString(), AuthResponse.class);

        MvcResult meResult = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + auth.getAccessToken()))
                .andExpect(status().isOk())
                .andReturn();

        CurrentUserResponse me = objectMapper.readValue(
                meResult.getResponse().getContentAsString(), CurrentUserResponse.class);

        return new UserContext(auth, me);
    }

    private String createInvitedMembership(UUID userId, UUID orgId) {
        String inviteToken = "test-invite-" + UUID.randomUUID();
        String tokenHash = CryptoUtils.hashApiKey(inviteToken);
        Membership membership = Membership.builder()
                .userId(userId)
                .organizationId(orgId)
                .role(MembershipRole.DEVELOPER)
                .status(MembershipStatus.INVITED)
                .inviteTokenHash(tokenHash)
                .inviteExpiresAt(Instant.now().plus(48, ChronoUnit.HOURS))
                .build();
        membershipRepository.saveAndFlush(membership);
        return inviteToken;
    }

    @Test
    public void testAcceptInvite_wrongUser_returns403() throws Exception {
        UserContext owner = registerUser("sec-owner1@test.com", "SecOrg1");
        UUID orgId = owner.currentUser().getOrganization().getId();

        UserContext invitee = registerUser("sec-invitee1@test.com", "InviteeOrg1");
        UUID inviteeUserId = invitee.currentUser().getUser().getId();

        String inviteToken = createInvitedMembership(inviteeUserId, orgId);

        UserContext attacker = registerUser("sec-attacker1@test.com", "AttackerOrg1");

        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members/accept-invite")
                        .param("token", inviteToken)
                        .header("Authorization", "Bearer " + attacker.auth().getAccessToken()))
                .andExpect(status().isForbidden());

        String tokenHash = CryptoUtils.hashApiKey(inviteToken);
        Membership m = membershipRepository.findByInviteTokenHash(tokenHash).orElseThrow();
        assertEquals(MembershipStatus.INVITED, m.getStatus());
    }

    @Test
    public void testAcceptInvite_wrongOrgId_returns403() throws Exception {
        UserContext owner = registerUser("sec-owner2@test.com", "SecOrg2");
        UUID orgId = owner.currentUser().getOrganization().getId();

        UserContext invitee = registerUser("sec-invitee2@test.com", "InviteeOrg2");
        UUID inviteeUserId = invitee.currentUser().getUser().getId();
        UUID wrongOrgId = invitee.currentUser().getOrganization().getId();

        String inviteToken = createInvitedMembership(inviteeUserId, orgId);

        mockMvc.perform(post("/api/v1/orgs/" + wrongOrgId + "/members/accept-invite")
                        .param("token", inviteToken)
                        .header("Authorization", "Bearer " + invitee.auth().getAccessToken()))
                .andExpect(status().isForbidden());

        String tokenHash = CryptoUtils.hashApiKey(inviteToken);
        Membership m = membershipRepository.findByInviteTokenHash(tokenHash).orElseThrow();
        assertEquals(MembershipStatus.INVITED, m.getStatus());
    }

    @Test
    public void testAcceptInvite_fabricatedOrgId_returns403() throws Exception {
        UserContext owner = registerUser("sec-owner3@test.com", "SecOrg3");
        UUID orgId = owner.currentUser().getOrganization().getId();

        UserContext invitee = registerUser("sec-invitee3@test.com", "InviteeOrg3");
        UUID inviteeUserId = invitee.currentUser().getUser().getId();

        String inviteToken = createInvitedMembership(inviteeUserId, orgId);

        mockMvc.perform(post("/api/v1/orgs/" + UUID.randomUUID() + "/members/accept-invite")
                        .param("token", inviteToken)
                        .header("Authorization", "Bearer " + invitee.auth().getAccessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testAcceptInvite_correctUserAndOrg_returns200() throws Exception {
        UserContext owner = registerUser("sec-owner4@test.com", "SecOrg4");
        UUID orgId = owner.currentUser().getOrganization().getId();

        UserContext invitee = registerUser("sec-invitee4@test.com", "InviteeOrg4");
        UUID inviteeUserId = invitee.currentUser().getUser().getId();

        String inviteToken = createInvitedMembership(inviteeUserId, orgId);

        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members/accept-invite")
                        .param("token", inviteToken)
                        .header("Authorization", "Bearer " + invitee.auth().getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));

        String tokenHash = CryptoUtils.hashApiKey(inviteToken);
        assertFalse(membershipRepository.findByInviteTokenHash(tokenHash).isPresent(),
                "Invite token hash should be cleared after acceptance");
    }

    // Email is off here, so the returned link is the only copy of the invite.
    @Test
    public void testInviteResponseCarriesAWorkingLinkAndTheListingDoesNot() throws Exception {
        UserContext owner = registerUser("link-owner@test.com", "LinkOrg");
        UUID orgId = owner.currentUser().getOrganization().getId();

        MemberResponse invited = addMember(owner, orgId, "link-invitee@test.com");

        assertEquals(MembershipStatus.INVITED, invited.getStatus());
        assertNotNull(invited.getInviteExpiresAt(), "a pending invite states when it stops working");
        assertNotNull(invited.getInviteUrl(), "the owner has no other way to deliver the invite");

        Membership membership = membershipRepository
                .findByInviteTokenHash(CryptoUtils.hashApiKey(tokenOf(invited.getInviteUrl())))
                .orElseThrow(() -> new AssertionError("the returned link does not carry the stored token"));
        assertEquals(invited.getUserId(), membership.getUserId());

        // Every member can read the listing, so it carries the expiry and never the link.
        mockMvc.perform(get("/api/v1/orgs/" + orgId + "/members")
                        .header("Authorization", "Bearer " + owner.auth().getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.status == 'INVITED')].inviteExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$[?(@.inviteUrl != null)]").isEmpty());
    }

    @Test
    public void testReissuingAnInviteRetiresThePreviousLink() throws Exception {
        UserContext owner = registerUser("reissue-owner@test.com", "ReissueOrg");
        UUID orgId = owner.currentUser().getOrganization().getId();

        MemberResponse invited = addMember(owner, orgId, "reissue-invitee@test.com");
        String staleToken = tokenOf(invited.getInviteUrl());

        MvcResult reissueResult = mockMvc.perform(
                        post("/api/v1/orgs/" + orgId + "/members/" + invited.getUserId() + "/invite")
                                .header("Authorization", "Bearer " + owner.auth().getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andReturn();

        MemberResponse reissued = objectMapper.readValue(
                reissueResult.getResponse().getContentAsString(), MemberResponse.class);
        String freshToken = tokenOf(reissued.getInviteUrl());

        assertNotEquals(staleToken, freshToken, "re-issuing must mint a new token");
        assertFalse(membershipRepository.findByInviteTokenHash(CryptoUtils.hashApiKey(staleToken)).isPresent(),
                "the link that was handed out before must stop working");
        assertTrue(membershipRepository.findByInviteTokenHash(CryptoUtils.hashApiKey(freshToken)).isPresent(),
                "the link handed out now must work");
        assertTrue(reissued.getInviteExpiresAt().isAfter(invited.getInviteExpiresAt()),
                "re-issuing restarts the expiry");
    }

    private MemberResponse addMember(UserContext owner, UUID orgId, String email) throws Exception {
        AddMemberRequest request = AddMemberRequest.builder()
                .email(email)
                .role(MembershipRole.DEVELOPER)
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Authorization", "Bearer " + owner.auth().getAccessToken()))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), MemberResponse.class);
    }

    private String tokenOf(String inviteUrl) {
        return UriComponentsBuilder.fromUriString(inviteUrl).build()
                .getQueryParams().getFirst("token");
    }
}
