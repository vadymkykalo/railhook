package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.tenancy.TenantContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Suspending a member, rather than deleting them.
 *
 * <p>Removal was the only lever an owner had: a colleague on leave, a stolen laptop or a
 * half-finished offboarding all cost the membership row, and with it the record of who that
 * person was and what they held. {@code MembershipStatus.DISABLED} was in the enum the whole
 * time and nothing ever assigned it.</p>
 *
 * <p>What a suspension has to be, and what these tests pin down: the row and the role survive,
 * the live access token stops working immediately (the same epoch revocation a demotion and a
 * removal already do — an access token is self-contained, so without it the suspension only
 * starts a quarter of an hour later), and it cannot be used to make an organization
 * unadministrable — not by an owner suspending themselves, and not by suspending the last
 * owner who is still able to sign in.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipSuspensionTest {

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private EmailService emailService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private MembershipService membershipService;
    private UUID organizationId;
    private UUID ownerId;
    private UUID memberId;
    private Membership membership;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        TenantContext.set(organizationId);

        // Cost 4: MembershipService takes the shared password encoder bean now, and the
        // production cost of 12 would add ~200ms per test for no assertion's benefit.
        membershipService = new MembershipService(
                userRepository, membershipRepository, emailService, tokenBlacklistService,
                new BCryptPasswordEncoder(4));

        membership = new Membership();
        membership.setUserId(memberId);
        membership.setOrganizationId(organizationId);
        membership.setRole(MembershipRole.DEVELOPER);
        membership.setStatus(MembershipStatus.ACTIVE);

        User user = new User();
        user.setId(memberId);
        user.setEmail("member@example.com");

        when(membershipRepository.findByUserIdAndOrganizationId(memberId, organizationId))
                .thenReturn(Optional.of(membership));
        when(userRepository.findById(memberId)).thenReturn(Optional.of(user));
        when(membershipRepository.save(any(Membership.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a suspended member keeps their row and their role, and is marked DISABLED")
    void suspendKeepsTheMembershipAndTheRole() {
        var response = membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.DISABLED);
        assertThat(membership.getRole()).isEqualTo(MembershipRole.DEVELOPER);
        assertThat(response.getStatus()).isEqualTo(MembershipStatus.DISABLED);
        assertThat(response.getRole()).isEqualTo(MembershipRole.DEVELOPER);
        verify(membershipRepository, never()).delete(any(Membership.class));
    }

    @Test
    @DisplayName("suspending ends the member's current sessions, not only their next login")
    void suspendEndsCurrentSessions() {
        membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER);

        verify(tokenBlacklistService).revokeAllUserTokens(memberId);
    }

    @Test
    @DisplayName("only an owner can suspend")
    void suspendRequiresOwner() {
        assertThatThrownBy(() -> membershipService.suspendMember(memberId, ownerId, MembershipRole.DEVELOPER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("an owner cannot suspend themselves")
    void ownerCannotSuspendThemselves() {
        membership.setRole(MembershipRole.OWNER);
        when(membershipRepository.findByUserIdAndOrganizationId(ownerId, organizationId))
                .thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> membershipService.suspendMember(ownerId, ownerId, MembershipRole.OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        verify(tokenBlacklistService, never()).revokeAllUserTokens(any());
    }

    @Test
    @DisplayName("the last owner who can still sign in cannot be suspended")
    void lastOwnerCannotBeSuspended() {
        membership.setRole(MembershipRole.OWNER);
        when(membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                organizationId, MembershipRole.OWNER, MembershipStatus.DISABLED)).thenReturn(1L);

        assertThatThrownBy(() -> membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("an owner beside another owner who can still sign in may be suspended")
    void anOwnerBesideAnotherOwnerMayBeSuspended() {
        membership.setRole(MembershipRole.OWNER);
        when(membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                organizationId, MembershipRole.OWNER, MembershipStatus.DISABLED)).thenReturn(2L);

        membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.DISABLED);
    }

    @Test
    @DisplayName("an invite that has not been accepted is not a suspension's subject")
    void anInvitedMemberCannotBeSuspended() {
        membership.setStatus(MembershipStatus.INVITED);

        assertThatThrownBy(() -> membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("reinstating puts the member back to ACTIVE with the role they kept")
    void reinstateRestoresAccess() {
        membership.setStatus(MembershipStatus.DISABLED);

        var response = membershipService.reinstateMember(memberId, MembershipRole.OWNER);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(response.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(response.getRole()).isEqualTo(MembershipRole.DEVELOPER);
    }

    @Test
    @DisplayName("only an owner can reinstate")
    void reinstateRequiresOwner() {
        membership.setStatus(MembershipStatus.DISABLED);

        assertThatThrownBy(() -> membershipService.reinstateMember(memberId, MembershipRole.VIEWER))
                .isInstanceOfAny(ResponseStatusException.class, ForbiddenException.class);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.DISABLED);
    }

    @Test
    @DisplayName("reinstating a member who is not suspended is a conflict, not a silent no-op")
    void reinstateOnlyAppliesToASuspendedMember() {
        membership.setStatus(MembershipStatus.INVITED);

        assertThatThrownBy(() -> membershipService.reinstateMember(memberId, MembershipRole.OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.INVITED);
    }
}
