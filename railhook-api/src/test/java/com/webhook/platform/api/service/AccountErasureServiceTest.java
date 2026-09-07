package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
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

/**
 * Article 17, for a person rather than an organization. The platform could erase a whole
 * customer and could not erase one human being, which is the half of the right that individual
 * users actually exercise.
 *
 * <p>The shape below is decided by two facts about the schema rather than by preference:
 * {@code shared_debug_links.created_by} references {@code users(id)} with no cascade, so
 * deleting the row outright fails for anyone who ever shared a debug link; and
 * {@code audit_log.user_id} has no foreign key at all, so the record of what someone did
 * survives them — which is the point of an audit log, and is a legitimate basis for keeping it.
 * So the person is anonymised and the account is made unusable, rather than the row vanishing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountErasureService")
class AccountErasureServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private OrganizationService organizationService;
    @Mock private UserSessionService userSessionService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private AccountErasureService service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountErasureService(userRepository, membershipRepository,
                organizationService, userSessionService, tokenBlacklistService);
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

    @Nested
    @DisplayName("what is left behind")
    class Anonymisation {

        @Test
        @DisplayName("the person is unidentifiable afterwards")
        void personalDataIsGone() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(membershipRepository.findByUserId(userId)).thenReturn(List.of());

            service.eraseAccount(userId);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            User after = saved.getValue();
            assertThat(after.getEmail()).doesNotContain("someone@example.com");
            assertThat(after.getFullName()).isNull();
        }

        @Test
        @DisplayName("the replacement address can never receive mail or collide")
        void replacementAddressIsUnroutableAndUnique() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(membershipRepository.findByUserId(userId)).thenReturn(List.of());

            service.eraseAccount(userId);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            // .invalid is reserved by RFC 2606 and resolves nowhere, and the id keeps the
            // unique constraint on email satisfied however many accounts are erased.
            assertThat(saved.getValue().getEmail()).endsWith(".invalid");
            assertThat(saved.getValue().getEmail()).contains(userId.toString());
        }

        @Test
        @DisplayName("the account cannot be signed into or recovered")
        void accountCannotBeUsedAgain() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(membershipRepository.findByUserId(userId)).thenReturn(List.of());

            service.eraseAccount(userId);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            User after = saved.getValue();
            assertThat(after.getStatus()).isEqualTo(UserStatus.DISABLED);
            assertThat(after.getPasswordHash()).isNotEqualTo("$2a$12$originalhash");
            // A live reset token would be a way back into an erased account.
            assertThat(after.getPasswordResetToken()).isNull();
            assertThat(after.getVerificationToken()).isNull();
        }

        @Test
        @DisplayName("every open session is closed, not left to expire")
        void sessionsAreRevoked() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(membershipRepository.findByUserId(userId)).thenReturn(List.of());

            service.eraseAccount(userId);

            verify(userSessionService).revokeAllSessions(userId);
            // The access token is stateless and outlives the session row on its own.
            verify(tokenBlacklistService).revokeAllUserTokens(userId);
        }
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

            // Otherwise erasing the account leaves every event, endpoint and delivery it owned
            // sitting in the database with nobody able to reach or erase them.
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

            // And nothing at all has happened: erasure is all or none.
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

    @Test
    @DisplayName("erasing an account that is not there is not a silent success")
    void unknownUserIsRejected() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.eraseAccount(userId))
                .isInstanceOf(RuntimeException.class);
    }
}
