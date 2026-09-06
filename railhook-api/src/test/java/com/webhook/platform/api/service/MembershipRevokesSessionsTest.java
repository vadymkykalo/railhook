package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Demoting or removing a member has to take effect now, not in fifteen minutes.
 *
 * <p>{@code JwtAuthenticationFilter} reads {@code organizationId} and {@code role} straight
 * out of the access token and re-checks neither against the database. So a demoted OWNER goes
 * on exercising OWNER authority, and a removed member goes on reaching the organization's
 * data, for the whole remaining access-token lifetime. Refreshing is already blocked — the
 * refresh path 404s once the membership is gone — which is exactly why the access token is
 * the gap worth closing.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipRevokesSessionsTest {

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private EmailService emailService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private MembershipService membershipService;
    private UUID organizationId;
    private UUID memberId;
    private Membership membership;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        TenantContext.set(organizationId);

        membershipService = new MembershipService(
                userRepository, membershipRepository, emailService, tokenBlacklistService, new BCryptPasswordEncoder(4));

        membership = new Membership();
        membership.setUserId(memberId);
        membership.setOrganizationId(organizationId);
        membership.setRole(MembershipRole.DEVELOPER);

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
    void demotingAMemberEndsTheirCurrentSessions() {
        membership.setRole(MembershipRole.DEVELOPER);

        membershipService.changeMemberRole(memberId, MembershipRole.VIEWER, MembershipRole.OWNER);

        verify(tokenBlacklistService).revokeAllUserTokens(memberId);
    }

    @Test
    void removingAMemberEndsTheirCurrentSessions() {
        membershipService.removeMember(memberId, MembershipRole.OWNER);

        verify(tokenBlacklistService).revokeAllUserTokens(memberId);
    }
}
