package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.dto.MemberResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.tenancy.TenantContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An invite has to be deliverable by hand, because in the shipped default configuration
 * ({@code EMAIL_ENABLED=false}) nothing is delivered at all.
 *
 * <p>The token used to reach only the API container's log, while the browser was told
 * "Invitation sent". So the owner who issues an invite is handed the accept-invite link
 * back in the response, and can re-issue it when it expires. What is deliberately
 * <em>not</em> handed back is the temporary password minted for a brand-new invitee: it
 * is non-expiring full account access, and {@code EmailService#sendTemporaryPasswordEmail}
 * refuses to log or return it for exactly that reason. The invite link plus
 * forgot-password is the safe way in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipInviteTest {

    private static final String INVITE_BASE = "http://localhost:5173/accept-invite?token=";

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private EmailService emailService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private MembershipService membershipService;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        TenantContext.set(organizationId);

        // Cost 4: MembershipService now takes the shared encoder bean instead of building one,
        // and the production cost of 12 would add ~200ms to every test that creates an invitee
        // for no assertion's benefit.
        membershipService = new MembershipService(
                userRepository, membershipRepository, emailService, tokenBlacklistService,
                new BCryptPasswordEncoder(4));

        when(membershipRepository.save(any(Membership.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        when(emailService.inviteUrl(anyString(), anyString()))
                .thenAnswer(i -> INVITE_BASE + i.getArgument(1) + "&orgId=" + i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void invitingANewUserHandsTheOwnerTheLinkToPassOn() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        MemberResponse response = membershipService.addMember(
                AddMemberRequest.builder().email("new@example.com").role(MembershipRole.DEVELOPER).build(),
                MembershipRole.OWNER);

        assertThat(response.getStatus()).isEqualTo(MembershipStatus.INVITED);
        assertThat(response.getInviteUrl()).startsWith(INVITE_BASE);
        assertThat(response.getInviteUrl()).contains("orgId=" + organizationId);
        assertThat(response.getInviteExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void invitingAnExistingUserCarriesNoLinkBecauseThereIsNoInvite() {
        User existing = new User();
        existing.setId(UUID.randomUUID());
        existing.setEmail("known@example.com");
        when(userRepository.existsByEmail("known@example.com")).thenReturn(true);
        when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existing));

        MemberResponse response = membershipService.addMember(
                AddMemberRequest.builder().email("known@example.com").role(MembershipRole.VIEWER).build(),
                MembershipRole.OWNER);

        assertThat(response.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(response.getInviteUrl()).isNull();
        assertThat(response.getInviteExpiresAt()).isNull();
    }

    @Test
    void reissuingAnInviteMintsAFreshTokenAndPushesTheExpiryOut() {
        Membership pending = pendingInvite();
        String staleHash = pending.getInviteTokenHash();
        Instant staleExpiry = pending.getInviteExpiresAt();

        MemberResponse response = membershipService.reissueInvite(pending.getUserId(), MembershipRole.OWNER);

        assertThat(pending.getInviteTokenHash()).isNotEqualTo(staleHash);
        assertThat(pending.getInviteExpiresAt()).isAfter(staleExpiry);
        assertThat(pending.getStatus()).isEqualTo(MembershipStatus.INVITED);
        assertThat(response.getInviteUrl()).startsWith(INVITE_BASE);
        assertThat(response.getInviteExpiresAt()).isEqualTo(pending.getInviteExpiresAt());

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendInviteEmail(eq("pending@example.com"), eq(organizationId.toString()), token.capture());
        assertThat(response.getInviteUrl()).contains(token.getValue());
    }

    @Test
    void reissuingAnInviteNeverMintsAnotherTemporaryPassword() {
        Membership pending = pendingInvite();

        membershipService.reissueInvite(pending.getUserId(), MembershipRole.OWNER);

        verify(emailService, never()).sendTemporaryPasswordEmail(anyString(), anyString());
    }

    @Test
    void onlyAnOwnerMayReissueAnInvite() {
        Membership pending = pendingInvite();

        assertThatThrownBy(() -> membershipService.reissueInvite(pending.getUserId(), MembershipRole.DEVELOPER))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anAcceptedMembershipHasNoInviteToReissue() {
        Membership accepted = pendingInvite();
        accepted.setStatus(MembershipStatus.ACTIVE);
        accepted.setInviteTokenHash(null);
        accepted.setInviteExpiresAt(null);

        assertThatThrownBy(() -> membershipService.reissueInvite(accepted.getUserId(), MembershipRole.OWNER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listedMembersCarryTheirInviteExpiryButNeverTheLink() {
        Membership pending = pendingInvite();
        User user = new User();
        user.setId(pending.getUserId());
        user.setEmail("pending@example.com");
        when(membershipRepository.findMembersWithUsers(organizationId))
                .thenReturn(List.<Object[]>of(new Object[]{pending, user}));

        List<MemberResponse> members = membershipService.getOrganizationMembers();

        assertThat(members).singleElement().satisfies(member -> {
            assertThat(member.getInviteExpiresAt()).isEqualTo(pending.getInviteExpiresAt());
            assertThat(member.getInviteUrl()).isNull();
        });
    }

    /** A membership sitting at INVITED, registered with the repository mocks. */
    private Membership pendingInvite() {
        UUID userId = UUID.randomUUID();
        Membership membership = new Membership();
        membership.setUserId(userId);
        membership.setOrganizationId(organizationId);
        membership.setRole(MembershipRole.DEVELOPER);
        membership.setStatus(MembershipStatus.INVITED);
        membership.setInviteTokenHash("stale-hash");
        membership.setInviteExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        User user = new User();
        user.setId(userId);
        user.setEmail("pending@example.com");

        when(membershipRepository.findByUserIdAndOrganizationId(userId, organizationId))
                .thenReturn(Optional.of(membership));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        return membership;
    }
}
