package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.common.util.CryptoUtils;
import com.webhook.platform.api.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A password change has to end the sessions that the old password could have opened.
 *
 * <p>Access tokens are self-contained and valid until they expire — nothing consults the
 * database on each request — so changing the password without revoking them leaves whoever
 * holds one still signed in. In the case this most matters, recovering a compromised account,
 * that is the attacker: they keep full access for the remainder of the access-token TTL while
 * the owner believes they have just locked them out.</p>
 *
 * <p>The mechanism was already here — {@code TokenBlacklistService.revokeAllUserTokens} bumps
 * a per-user epoch that {@code JwtAuthenticationFilter} checks — and was wired into
 * refresh-token reuse detection, but not into either password path.</p>
 *
 * <p>It now goes through {@code UserSessionService.revokeAllSessions}, which bumps that same
 * epoch <em>and</em> marks the {@code user_sessions} rows revoked. Both halves matter: the epoch
 * is what stops the tokens, the rows are what stops the session list from still showing a device
 * as live after its owner has just changed their password to get rid of it.</p>
 */
@ExtendWith(MockitoExtension.class)
class PasswordChangeRevokesSessionsTest {

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
    private User user;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, organizationRepository, membershipRepository,
                planRepository, jwtUtil, new BCryptPasswordEncoder(4), tokenBlacklistService,
                userSessionService, accountLockoutService, emailService, false);

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

        // The whole point of a reset is that whoever had access before does not any more.
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
