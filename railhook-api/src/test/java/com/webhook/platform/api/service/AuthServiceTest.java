package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.UserSession;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.dto.SwitchOrganizationRequest;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.security.JwtUtil;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private PlanRepository planRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private UserSessionService userSessionService;
    @Mock private AccountLockoutService accountLockoutService;
    @Mock private EmailService emailService;

    private AuthService authService;

    @BeforeEach
    void buildService() {
        authService = authService(jwtUtil, new BCryptPasswordEncoder(4), accountLockoutService, false);
    }

    private AuthService authService(JwtUtil jwt, BCryptPasswordEncoder encoder,
                                    AccountLockoutService lockout, boolean emailVerificationRequired) {
        return new AuthService(userRepository, organizationRepository, membershipRepository,
                planRepository, jwt, encoder, tokenBlacklistService, userSessionService, lockout,
                emailService, mock(VerificationMailBudget.class), mock(OnboardingMailService.class),
                emailVerificationRequired);
    }

    // The oldest membership once won permanently; the organization now lives on the session.
    @Nested
    @MockitoSettings(strictness = Strictness.STRICT_STUBS)
    @DisplayName("AuthService.switchOrganization — a token for another organization you belong to")
    class OrganizationSwitch {

        private JwtUtil realJwt;

        private final UUID userId = UUID.randomUUID();
        private final UUID homeOrgId = UUID.randomUUID();
        private final UUID clientOrgId = UUID.randomUUID();
        private final UUID strangerOrgId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();

        private String refreshToken;
        private UserSession session;

        @BeforeEach
        void setUp() {
            realJwt = new JwtUtil("a-test-secret-that-is-long-enough-32", 900_000L, 86_400_000L);
            authService = authService(realJwt, new BCryptPasswordEncoder(4), accountLockoutService, false);

            refreshToken = realJwt.generateRefreshToken(userId, sessionId);
            session = UserSession.builder()
                    .id(sessionId)
                    .userId(userId)
                    .organizationId(homeOrgId)
                    .refreshTokenJti(realJwt.getJtiFromToken(refreshToken))
                    .client(SessionClient.WEB)
                    .lastSeenAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(86_400))
                    .build();
        }

        private User user() {
            return User.builder().id(userId).email("multi@example.com").passwordHash("x")
                    .status(UserStatus.ACTIVE).emailVerified(true).build();
        }

        private SwitchOrganizationRequest to(UUID organizationId) {
            return SwitchOrganizationRequest.builder().organizationId(organizationId).build();
        }

        @Test
        @DisplayName("mints a token for the target organization and moves the session onto it")
        void switchesToASecondOrganization() {
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));
            when(membershipRepository.findByUserIdAndOrganizationId(userId, clientOrgId))
                    .thenReturn(Optional.of(membership(clientOrgId, MembershipRole.DEVELOPER)));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

            AuthResponse response = authService.switchOrganization(userId, to(clientOrgId), refreshToken);

            assertThat(realJwt.getOrganizationIdFromToken(response.getAccessToken())).isEqualTo(clientOrgId);
            assertThat(session.getOrganizationId())
                    .as("the session remembers, or the next refresh would snap back to the old organization")
                    .isEqualTo(clientOrgId);
            verify(userSessionService).save(session);
        }

        @Test
        @DisplayName("the role comes from the target membership, never from the token being replaced")
        void roleIsNotCarriedAcross() {
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));
            when(membershipRepository.findByUserIdAndOrganizationId(userId, clientOrgId))
                    .thenReturn(Optional.of(membership(clientOrgId, MembershipRole.VIEWER)));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

            AuthResponse response = authService.switchOrganization(userId, to(clientOrgId), refreshToken);

            assertThat(realJwt.getRoleFromToken(response.getAccessToken())).isEqualTo(MembershipRole.VIEWER);
        }

        @Test
        @DisplayName("an organization the caller is not a member of is refused, and nothing is minted")
        void refusesAnOrganizationTheCallerIsNotIn() {
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));
            when(membershipRepository.findByUserIdAndOrganizationId(userId, strangerOrgId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.switchOrganization(userId, to(strangerOrgId), refreshToken))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(session.getOrganizationId()).isEqualTo(homeOrgId);
            verify(userSessionService, never()).save(any());
        }

        @Test
        @DisplayName("a membership the organization suspended is refused exactly like no membership, and nothing is minted")
        void refusesASuspendedMembership() {
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));
            Membership suspended = membership(clientOrgId, MembershipRole.DEVELOPER);
            suspended.setStatus(MembershipStatus.DISABLED);
            when(membershipRepository.findByUserIdAndOrganizationId(userId, clientOrgId))
                    .thenReturn(Optional.of(suspended));
            lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

            assertThatThrownBy(() -> authService.switchOrganization(userId, to(clientOrgId), refreshToken))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("You are not a member of that organization");

            assertThat(session.getOrganizationId()).isEqualTo(homeOrgId);
            verify(userSessionService, never()).save(any());
        }

        @Test
        @DisplayName("a session belonging to someone else is not switchable, however valid its token")
        void refusesAnotherUsersSession() {
            session.setUserId(UUID.randomUUID());
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));

            assertThatThrownBy(() -> authService.switchOrganization(userId, to(clientOrgId), refreshToken))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("401");

            verify(membershipRepository, never()).findByUserIdAndOrganizationId(any(), any());
        }

        @Test
        @DisplayName("a signed-out session cannot be switched back into service")
        void refusesARevokedSession() {
            session.setRevokedAt(Instant.now().minusSeconds(5));
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));

            assertThatThrownBy(() -> authService.switchOrganization(userId, to(clientOrgId), refreshToken))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("401");
        }

        @Test
        @DisplayName("an access token presented in place of the refresh token is not accepted")
        void refusesAnAccessTokenAtTheRefreshSlot() {
            String accessToken = realJwt.generateAccessToken(userId, homeOrgId, MembershipRole.OWNER, sessionId, true);

            assertThatThrownBy(() -> authService.switchOrganization(userId, to(clientOrgId), accessToken))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("401");
        }

        @Test
        @DisplayName("switching invalidates nothing — no blacklisting, no new refresh token")
        void switchingInvalidatesNothing() {
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));
            when(membershipRepository.findByUserIdAndOrganizationId(userId, clientOrgId))
                    .thenReturn(Optional.of(membership(clientOrgId, MembershipRole.OWNER)));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

            AuthResponse first = authService.switchOrganization(userId, to(clientOrgId), refreshToken);
            AuthResponse second = authService.switchOrganization(userId, to(clientOrgId), refreshToken);

            assertThat(second.getRefreshToken()).isNull();
            assertThat(realJwt.getOrganizationIdFromToken(first.getAccessToken())).isEqualTo(clientOrgId);
            assertThat(realJwt.getOrganizationIdFromToken(second.getAccessToken())).isEqualTo(clientOrgId);
            verify(tokenBlacklistService, never()).blacklist(any(), any());
            verify(tokenBlacklistService, never()).revokeAllUserTokens(any());
            verify(tokenBlacklistService, never()).revokeSession(any(), any());
        }

        @Test
        @DisplayName("the new access token stays on the same session, so it is still revocable")
        void keepsTheSessionId() {
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));
            when(membershipRepository.findByUserIdAndOrganizationId(userId, clientOrgId))
                    .thenReturn(Optional.of(membership(clientOrgId, MembershipRole.OWNER)));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

            AuthResponse response = authService.switchOrganization(userId, to(clientOrgId), refreshToken);

            assertThat(realJwt.getSessionIdFromToken(response.getAccessToken())).isEqualTo(sessionId);
        }

        @Test
        @DisplayName("a refresh after switching stays in the chosen organization")
        void refreshHonoursTheChosenOrganization() {
            session.setOrganizationId(clientOrgId);
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
            when(membershipRepository.findByUserIdAndOrganizationId(userId, clientOrgId))
                    .thenReturn(Optional.of(membership(clientOrgId, MembershipRole.DEVELOPER)));

            AuthResponse response = authService.refreshToken(
                    refreshToken, SessionOrigin.of(SessionClient.WEB, "a-browser", "198.51.100.4"));

            assertThat(realJwt.getOrganizationIdFromToken(response.getAccessToken())).isEqualTo(clientOrgId);
            verify(membershipRepository, never()).findByUserIdOrderByCreatedAtAsc(any());
        }

        @Test
        @DisplayName("losing the membership you were looking at falls back rather than locking you out")
        void refreshFallsBackWhenTheMembershipIsGone() {
            session.setOrganizationId(clientOrgId);
            when(userSessionService.findByRefreshJti(session.getRefreshTokenJti()))
                    .thenReturn(Optional.of(session));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
            when(membershipRepository.findByUserIdAndOrganizationId(userId, clientOrgId))
                    .thenReturn(Optional.empty());
            when(membershipRepository.findByUserIdOrderByCreatedAtAsc(userId))
                    .thenReturn(List.of(membership(homeOrgId, MembershipRole.OWNER)));

            AuthResponse response = authService.refreshToken(
                    refreshToken, SessionOrigin.of(SessionClient.WEB, "a-browser", "198.51.100.4"));

            assertThat(realJwt.getOrganizationIdFromToken(response.getAccessToken())).isEqualTo(homeOrgId);
            assertThat(session.getOrganizationId())
                    .as("moved with the fallback, so the next refresh does not repeat the work")
                    .isEqualTo(homeOrgId);
        }

        @Test
        @DisplayName("a refresh token that is no longer its session's is refused, not rotated")
        void supersededRefreshTokenIsRefused() {
            when(userSessionService.findByRefreshJti(realJwt.getJtiFromToken(refreshToken)))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

            assertThatThrownBy(() -> authService.refreshToken(
                    refreshToken, SessionOrigin.of(SessionClient.WEB, "a-browser", "198.51.100.4")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("401");

            verify(tokenBlacklistService, never()).blacklist(eq(realJwt.getJtiFromToken(refreshToken)), any());
        }

        private Membership membership(UUID organizationId, MembershipRole role) {
            return Membership.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .organizationId(organizationId)
                    .role(role)
                    .build();
        }
    }

    // Access tokens outlive a password change unless the change revokes them.
    @Nested
    @MockitoSettings(strictness = Strictness.STRICT_STUBS)
    class PasswordChangeRevokesSessions {

        private User user;

        @BeforeEach
        void setUp() {
            user = new User();
            user.setId(UUID.randomUUID());
            user.setEmail("owner@example.com");
            user.setPasswordHash(new BCryptPasswordEncoder(4).encode("old-password"));
        }

        @Test
        void resetPasswordRevokesEveryLiveSession() {
            String token = "plaintext-reset-token";
            user.setPasswordResetToken(CryptoUtils.hashApiKey(token));
            user.setPasswordResetTokenExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
            when(userRepository.findByPasswordResetToken(CryptoUtils.hashApiKey(token)))
                    .thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            authService.resetPassword(token, "brand-new-password");

            verify(userSessionService).revokeAllSessions(user.getId());
        }

        @Test
        void changePasswordRevokesEveryLiveSession() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            authService.changePassword(user.getId(), "old-password", "brand-new-password");

            verify(userSessionService).revokeAllSessions(user.getId());
        }
    }

    // An account with no organization left was locked out for good.
    @Nested
    class SignInWithoutMembership {

        private static final String PASSWORD = "the-right-password";
        private static final SessionOrigin ORIGIN =
                SessionOrigin.of(SessionClient.WEB, "Mozilla/5.0", "198.51.100.4");

        private User user;

        @BeforeEach
        void setUp() {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
            authService = authService(jwtUtil, encoder,
                    new AccountLockoutService(userRepository, true, 3, 60, 900, 60), true);

            user = User.builder()
                    .id(UUID.randomUUID())
                    .email("bob@example.com")
                    .fullName("Bob")
                    .passwordHash(encoder.encode(PASSWORD))
                    .status(UserStatus.ACTIVE)
                    .emailVerified(true)
                    .build();
            when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
            List<Membership> stored = new ArrayList<>();
            when(membershipRepository.findByUserIdOrderByCreatedAtAsc(user.getId()))
                    .thenAnswer(inv -> List.copyOf(stored));
            when(planRepository.findByName("free")).thenReturn(Optional.of(Plan.builder().name("free").build()));
            when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> {
                Organization org = inv.getArgument(0);
                org.setId(UUID.randomUUID());
                return org;
            });
            when(membershipRepository.save(any(Membership.class))).thenAnswer(inv -> {
                stored.add(inv.getArgument(0));
                return inv.getArgument(0);
            });
            when(jwtUtil.generateRefreshToken(any(), any())).thenReturn("refresh");
            when(jwtUtil.getJtiFromToken("refresh")).thenReturn(UUID.randomUUID().toString());
            when(jwtUtil.getExpirationFromToken("refresh"))
                    .thenReturn(new Date(System.currentTimeMillis() + 86_400_000L));
        }

        @Test
        @DisplayName("a password sign-in with no organization left gets one of its own")
        void passwordSignIn() {
            LoginRequest request = new LoginRequest();
            request.setEmail(user.getEmail());
            request.setPassword(PASSWORD);

            authService.login(request, ORIGIN);

            assertOwnsANewOrganization();
        }

        @Test
        @DisplayName("so does a sign-in through Google")
        void externalSignIn() {
            authService.issueSessionFor(user, ORIGIN);

            assertOwnsANewOrganization();
        }

        private void assertOwnsANewOrganization() {
            ArgumentCaptor<Membership> membership = ArgumentCaptor.forClass(Membership.class);
            verify(membershipRepository).save(membership.capture());
            assertThat(membership.getValue().getUserId()).isEqualTo(user.getId());
            assertThat(membership.getValue().getRole()).isEqualTo(MembershipRole.OWNER);
            ArgumentCaptor<Organization> org = ArgumentCaptor.forClass(Organization.class);
            verify(organizationRepository).save(org.capture());
            assertThat(org.getValue().getName()).isNotBlank();
        }
    }

    // Login and refresh mint tokens from a Membership, so that is where suspension is refused.
    @Nested
    class SuspendedMembershipDeniesAccess {

        private static final SessionOrigin WEB_ORIGIN =
                SessionOrigin.of(SessionClient.WEB, "vitest", "203.0.113.9");

        private User user;
        private UUID suspendedOrgId;
        private UUID activeOrgId;

        @BeforeEach
        void setUp() {
            user = new User();
            user.setId(UUID.randomUUID());
            user.setEmail("member@example.com");
            user.setEmailVerified(true);
            user.setStatus(UserStatus.ACTIVE);
            user.setPasswordHash(new BCryptPasswordEncoder(4).encode("correct-password"));

            suspendedOrgId = UUID.randomUUID();
            activeOrgId = UUID.randomUUID();

            when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(jwtUtil.generateAccessToken(any(), any(), any(), any(), anyBoolean())).thenReturn("access");
            when(jwtUtil.generateRefreshToken(any(), any())).thenReturn("refresh");
            when(jwtUtil.validateToken(any())).thenReturn(true);
            when(jwtUtil.getTokenType(any())).thenReturn(JwtUtil.TOKEN_TYPE_REFRESH);
            when(jwtUtil.getJtiFromToken(any())).thenReturn(UUID.randomUUID().toString());
            when(jwtUtil.getUserIdFromToken(any())).thenReturn(user.getId());
            when(jwtUtil.getExpirationFromToken(any())).thenReturn(Date.from(Instant.now().plusSeconds(3600)));
            when(tokenBlacklistService.isBlacklisted(any())).thenReturn(false);
            when(tokenBlacklistService.isTokenRevokedByEpoch(any(), any())).thenReturn(false);
            when(userSessionService.findByRefreshJti(any())).thenReturn(Optional.empty());
            when(jwtUtil.getSessionIdFromToken(any())).thenReturn(null);
            when(accountLockoutService.isLocked(any())).thenReturn(false);
        }

        private Membership membership(UUID organizationId, MembershipRole role, MembershipStatus status) {
            Membership membership = new Membership();
            membership.setUserId(user.getId());
            membership.setOrganizationId(organizationId);
            membership.setRole(role);
            membership.setStatus(status);
            return membership;
        }

        @Test
        @DisplayName("a suspended member's password is still correct and still gets them nowhere")
        void loginIsRefusedWhenTheOnlyMembershipIsSuspended() {
            when(membershipRepository.findByUserIdOrderByCreatedAtAsc(user.getId()))
                    .thenReturn(List.of(membership(suspendedOrgId, MembershipRole.DEVELOPER, MembershipStatus.DISABLED)));

            assertThatThrownBy(() -> authService.login(
                    LoginRequest.builder().email(user.getEmail()).password("correct-password").build(), WEB_ORIGIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);

            verify(jwtUtil, never()).generateAccessToken(any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a refresh does not mint a new token for an organization the member was suspended from")
        void refreshIsRefusedWhenTheOnlyMembershipIsSuspended() {
            when(membershipRepository.findByUserIdOrderByCreatedAtAsc(user.getId()))
                    .thenReturn(List.of(membership(suspendedOrgId, MembershipRole.DEVELOPER, MembershipStatus.DISABLED)));

            assertThatThrownBy(() -> authService.refreshToken("refresh-token", WEB_ORIGIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);

            verify(jwtUtil, never()).generateAccessToken(any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("suspended in one organization, still a member of another: the token names the other")
        void loginSkipsTheSuspendedMembership() {
            when(membershipRepository.findByUserIdOrderByCreatedAtAsc(user.getId()))
                    .thenReturn(List.of(
                            membership(suspendedOrgId, MembershipRole.OWNER, MembershipStatus.DISABLED),
                            membership(activeOrgId, MembershipRole.VIEWER, MembershipStatus.ACTIVE)));

            var response = authService.login(
                    LoginRequest.builder().email(user.getEmail()).password("correct-password").build(), WEB_ORIGIN);

            assertThat(response.getAccessToken()).isEqualTo("access");
            verify(jwtUtil).generateAccessToken(eq(user.getId()), eq(activeOrgId), eq(MembershipRole.VIEWER), any(), anyBoolean());
        }

        @Test
        @DisplayName("an invited member can still sign in to accept the invite")
        void loginStillWorksForAnInvitedMembership() {
            when(membershipRepository.findByUserIdOrderByCreatedAtAsc(user.getId()))
                    .thenReturn(List.of(membership(activeOrgId, MembershipRole.DEVELOPER, MembershipStatus.INVITED)));

            authService.login(LoginRequest.builder().email(user.getEmail()).password("correct-password").build(), WEB_ORIGIN);

            verify(jwtUtil).generateAccessToken(eq(user.getId()), eq(activeOrgId), eq(MembershipRole.DEVELOPER), any(), anyBoolean());
        }
    }
}
