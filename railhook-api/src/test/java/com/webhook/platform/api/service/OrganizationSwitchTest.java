package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.entity.UserSession;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.SwitchOrganizationRequest;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reaching a second organization at all, and not reaching a third.
 *
 * <p>Login and refresh both took {@code findByUserIdOrderByCreatedAtAsc(...).findFirst()}, so
 * the oldest membership won permanently: anyone who accepted an invite to a second organization
 * had no way to look at it, and the {@code GET /api/v1/orgs} endpoint that listed both had no
 * counterpart that could act on the answer. The organization now lives on the session, which is
 * what makes the choice survive the next refresh fifteen minutes later.
 *
 * <p>Most of these tests are about what must <em>not</em> happen. The endpoint takes an
 * organization as caller input — the only one in the API that does — so the interesting cases
 * are the ones where the caller asks for something they are not entitled to, and the one where
 * they are entitled but at a lower privilege than the token they currently hold.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService.switchOrganization — a token for another organization you belong to")
class OrganizationSwitchTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private PlanRepository planRepository;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private UserSessionService userSessionService;
    @Mock private AccountLockoutService accountLockoutService;
    @Mock private EmailService emailService;

    private JwtUtil jwtUtil;
    private AuthService authService;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeOrgId = UUID.randomUUID();
    private final UUID clientOrgId = UUID.randomUUID();
    private final UUID strangerOrgId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private String refreshToken;
    private UserSession session;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("a-test-secret-that-is-long-enough-32", 900_000L, 86_400_000L);
        authService = new AuthService(userRepository, organizationRepository, membershipRepository,
                planRepository, jwtUtil, new BCryptPasswordEncoder(4), tokenBlacklistService,
                userSessionService, accountLockoutService, emailService, false);

        refreshToken = jwtUtil.generateRefreshToken(userId, sessionId);
        session = UserSession.builder()
                .id(sessionId)
                .userId(userId)
                .organizationId(homeOrgId)
                .refreshTokenJti(jwtUtil.getJtiFromToken(refreshToken))
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

        assertThat(jwtUtil.getOrganizationIdFromToken(response.getAccessToken())).isEqualTo(clientOrgId);
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

        /* An OWNER of their own organization who is a VIEWER in a client's must arrive as a
           VIEWER. Carrying the old role across would be a privilege escalation that looks like
           a navigation action. */
        assertThat(jwtUtil.getRoleFromToken(response.getAccessToken())).isEqualTo(MembershipRole.VIEWER);
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
        String accessToken = jwtUtil.generateAccessToken(userId, homeOrgId, MembershipRole.OWNER, sessionId, true);

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

        /* A switcher is a thing people double-click. If it rotated the refresh token, the second
           click would present a token the first had just blacklisted -- which the reuse detection
           in refreshToken() reads as a stolen token family and answers by revoking every session
           the user has. Idempotent instead. */
        assertThat(second.getRefreshToken()).isNull();
        assertThat(jwtUtil.getOrganizationIdFromToken(first.getAccessToken())).isEqualTo(clientOrgId);
        assertThat(jwtUtil.getOrganizationIdFromToken(second.getAccessToken())).isEqualTo(clientOrgId);
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

        assertThat(jwtUtil.getSessionIdFromToken(response.getAccessToken())).isEqualTo(sessionId);
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

        /* This is the half that was actually broken. Before the session remembered, refresh went
           back to findFirst() over the memberships and quietly returned the user to their oldest
           organization a quarter of an hour after they chose another one. */
        assertThat(jwtUtil.getOrganizationIdFromToken(response.getAccessToken())).isEqualTo(clientOrgId);
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

        assertThat(jwtUtil.getOrganizationIdFromToken(response.getAccessToken())).isEqualTo(homeOrgId);
        assertThat(session.getOrganizationId())
                .as("moved with the fallback, so the next refresh does not repeat the work")
                .isEqualTo(homeOrgId);
    }

    @Test
    @DisplayName("a refresh token that is no longer its session's is refused, not rotated")
    void supersededRefreshTokenIsRefused() {
        when(userSessionService.findByRefreshJti(jwtUtil.getJtiFromToken(refreshToken)))
                .thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

        /* The shape a replayed token has. The Redis blacklist catches it while its entry lives;
           the session row catches it afterwards too, which is the point of having the durable
           half at all. */
        assertThatThrownBy(() -> authService.refreshToken(
                refreshToken, SessionOrigin.of(SessionClient.WEB, "a-browser", "198.51.100.4")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");

        verify(tokenBlacklistService, never()).blacklist(eq(jwtUtil.getJtiFromToken(refreshToken)), any());
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
