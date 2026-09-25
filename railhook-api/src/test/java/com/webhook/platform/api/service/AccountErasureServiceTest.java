package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.EmailChangeRequestRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.VerificationEmailSendRepository;
import com.webhook.platform.api.domain.repository.UserIdentityRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// An erased person is anonymised rather than deleted: shared_debug_links references users(id) without cascade.
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountErasureService")
class AccountErasureServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private OrganizationService organizationService;
    @Mock private UserSessionService userSessionService;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private UserIdentityRepository userIdentityRepository;
    @Mock private EmailChangeRequestRepository emailChangeRequestRepository;
    @Mock private VerificationEmailSendRepository verificationEmailSendRepository;
    @Mock private TunnelService tunnelService;

    private AccountErasureService service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountErasureService(userRepository, membershipRepository,
                organizationService, userSessionService, tokenBlacklistService, userIdentityRepository,
                emailChangeRequestRepository, verificationEmailSendRepository, tunnelService);
        userId = UUID.randomUUID();
        user = User.builder()
                .id(userId)
                .email("someone@example.com")
                .fullName("Some One")
                .passwordHash("$2a$12$originalhash")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .verificationToken("a-verification-token")
                .passwordResetToken("a-reset-token")
                .passwordResetTokenExpiresAt(Instant.now())
                .build();
    }

    private Membership membership(UUID orgId, MembershipRole role) {
        return Membership.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .organizationId(orgId)
                .role(role)
                .status(MembershipStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("the person is unidentifiable afterwards and the account cannot be used or recovered")
    void erasedAccountIsAnonymisedAndDead() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(membershipRepository.findByUserId(userId)).thenReturn(List.of());

        service.eraseAccount(userId);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User after = saved.getValue();
        assertThat(after.getEmail()).doesNotContain("someone@example.com")
                .endsWith(".invalid").contains(userId.toString());
        assertThat(after.getFullName()).isNull();
        assertThat(after.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(after.getPasswordHash()).isNotEqualTo("$2a$12$originalhash");
        assertThat(after.getPasswordResetToken()).isNull();
        assertThat(after.getVerificationToken()).isNull();
        verify(userSessionService).revokeAllSessions(userId);
        // The access token is stateless and outlives the session row on its own.
        verify(tokenBlacklistService).revokeAllUserTokens(userId);
        verify(userIdentityRepository).deleteByUserId(userId);
        verify(emailChangeRequestRepository).deleteByUserId(userId);
        verify(verificationEmailSendRepository).deleteByUserId(userId);
    }

    @Nested
    @DisplayName("what happens to the organizations")
    class Organizations {

        @Test
        @DisplayName("an organization the person was alone in goes with them")
        void soleMemberOrganizationIsDeleted() {
            UUID orgId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(membershipRepository.findByUserId(userId))
                    .thenReturn(List.of(membership(orgId, MembershipRole.OWNER)));
            when(membershipRepository.countByOrganizationId(orgId)).thenReturn(1L);

            service.eraseAccount(userId);

            verify(organizationService).deleteOrganizationById(orgId);
        }

        @Test
        @DisplayName("an organization with other people in it is left alone")
        void sharedOrganizationSurvives() {
            UUID orgId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(membershipRepository.findByUserId(userId))
                    .thenReturn(List.of(membership(orgId, MembershipRole.DEVELOPER)));
            when(membershipRepository.countByOrganizationId(orgId)).thenReturn(4L);

            service.eraseAccount(userId);

            verify(organizationService, never()).deleteOrganizationById(any());
            verify(membershipRepository).deleteAll(any());
        }

        @Test
        @DisplayName("the last owner of an organization other people still use cannot walk away")
        void lastOwnerOfASharedOrganizationIsRefused() {
            UUID orgId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(membershipRepository.findByUserId(userId))
                    .thenReturn(List.of(membership(orgId, MembershipRole.OWNER)));
            when(membershipRepository.countByOrganizationId(orgId)).thenReturn(3L);
            when(membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                    orgId, MembershipRole.OWNER, MembershipStatus.DISABLED)).thenReturn(1L);

            assertThatThrownBy(() -> service.eraseAccount(userId))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("owner");

            verify(userRepository, never()).save(any());
            verify(organizationService, never()).deleteOrganizationById(any());
        }

        @Test
        @DisplayName("one of several owners may leave")
        void oneOfSeveralOwnersMayLeave() {
            UUID orgId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(membershipRepository.findByUserId(userId))
                    .thenReturn(List.of(membership(orgId, MembershipRole.OWNER)));
            when(membershipRepository.countByOrganizationId(orgId)).thenReturn(3L);
            when(membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                    orgId, MembershipRole.OWNER, MembershipStatus.DISABLED)).thenReturn(2L);

            service.eraseAccount(userId);

            verify(userRepository).save(any());
        }
    }

}
