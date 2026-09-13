package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a suspension is actually refused.
 *
 * <p>Nothing re-reads a Membership on a request: {@code JwtAuthenticationFilter} takes the
 * organization and the role straight off the access token. The one place a Membership becomes an
 * authenticated context is where a token is minted from it — login and refresh — so that is where
 * a suspended membership has to stop being a way in, rather than in a check added per endpoint.
 * The live token is closed off separately, by the epoch revocation {@code suspendMember} does.</p>
 *
 * <p>A suspension is per organization, not per account, so a member suspended in one organization
 * and active in another is still that other organization's member. The membership the token names
 * has to skip the suspended one rather than the login being refused outright.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SuspendedMembershipDeniesAccessTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private PlanRepository planRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private UserSessionService userSessionService;
    @Mock private AccountLockoutService accountLockoutService;
    @Mock private EmailService emailService;

    private static final SessionOrigin WEB_ORIGIN =
            SessionOrigin.of(SessionClient.WEB, "vitest", "203.0.113.9");

    private AuthService authService;
    private User user;
    private UUID suspendedOrgId;
    private UUID activeOrgId;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, organizationRepository, membershipRepository,
                planRepository, jwtUtil, new BCryptPasswordEncoder(4), tokenBlacklistService,
                userSessionService, accountLockoutService, emailService, false);

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
        when(jwtUtil.getExpirationFromToken(any())).thenReturn(java.util.Date.from(Instant.now().plusSeconds(3600)));
        when(tokenBlacklistService.isBlacklisted(any())).thenReturn(false);
        when(tokenBlacklistService.isTokenRevokedByEpoch(any(), any())).thenReturn(false);
        // No session row: refreshToken then falls back to the oldest membership it may issue
        // for, which is the path these tests are about.
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
