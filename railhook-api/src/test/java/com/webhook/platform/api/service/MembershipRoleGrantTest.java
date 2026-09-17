package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which roles an owner can hand out.
 *
 * <p>OWNER is not granted through the member endpoints, and API_KEY is not a human role at all.
 * Adding a member and changing a member's role are two doors into the same grant, so both refuse
 * the same roles with the same answer — adding a member used to accept either.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipRoleGrantTest {

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
        membershipService = new MembershipService(
                userRepository, membershipRepository, emailService, tokenBlacklistService,
                new BCryptPasswordEncoder(4), mock(TunnelService.class));
        when(membershipRepository.save(any(Membership.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @EnumSource(value = MembershipRole.class, names = {"OWNER", "API_KEY"})
    @DisplayName("adding a member refuses a role that cannot be granted, and creates nothing")
    void addMemberRefusesUngrantableRoles(MembershipRole role) {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> membershipService.addMember(
                AddMemberRequest.builder().email("new@example.com").role(role).build(),
                MembershipRole.OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(membershipRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = MembershipRole.class, names = {"OWNER", "API_KEY"})
    @DisplayName("changing a member's role refuses the same roles, and leaves the membership as it was")
    void changeMemberRoleRefusesUngrantableRoles(MembershipRole role) {
        UUID memberId = UUID.randomUUID();
        Membership membership = new Membership();
        membership.setUserId(memberId);
        membership.setOrganizationId(organizationId);
        membership.setRole(MembershipRole.DEVELOPER);
        membership.setStatus(MembershipStatus.ACTIVE);
        when(membershipRepository.findByUserIdAndOrganizationId(memberId, organizationId))
                .thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> membershipService.changeMemberRole(memberId, role, MembershipRole.OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(membership.getRole()).isEqualTo(MembershipRole.DEVELOPER);
    }
}
