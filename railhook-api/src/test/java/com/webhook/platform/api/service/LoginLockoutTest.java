package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

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
 * Where the lockout meets the login.
 *
 * <p>{@code AccountLockoutServiceTest} covers the counting; this covers the three decisions the
 * login path makes with it, each of which would be a plausible thing to get wrong:
 *
 * <ul>
 *   <li>the lock is checked <em>before</em> the password, so an attack cannot make the server
 *       spend a deliberately-expensive BCrypt hash on every one of its attempts;</li>
 *   <li>a correct password clears the count, so an account in daily use never drifts into a
 *       lockout;</li>
 *   <li>a locked account is refused even when the password presented is correct — otherwise the
 *       lockout stops exactly the attacker who has already won.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService.login — what the lockout does to a sign-in")
class LoginLockoutTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private PlanRepository planRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private UserSessionService userSessionService;
    @Mock private EmailService emailService;

    private AccountLockoutService lockout;
    private AuthService authService;
    private User user;

    private static final String PASSWORD = "the-right-password";
    private static final SessionOrigin ORIGIN =
            SessionOrigin.of(SessionClient.WEB, "Mozilla/5.0", "198.51.100.4");

    @BeforeEach
    void setUp() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        lockout = new AccountLockoutService(userRepository, true, 3, 60, 900, 60);
        authService = new AuthService(userRepository, organizationRepository, membershipRepository,
                planRepository, jwtUtil, encoder, tokenBlacklistService, userSessionService,
                lockout, emailService, false);

        user = User.builder()
                .id(UUID.randomUUID())
                .email("target@example.com")
                .passwordHash(encoder.encode(PASSWORD))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
    }

    private LoginRequest attempt(String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword(password);
        return request;
    }

    private void expectLookup() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("the threshold-th wrong password locks the account, and the next try is 423")
    void wrongPasswordsEventuallyLock() {
        expectLookup();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    @DisplayName("the message names the way out, because a lockout with no exit is an outage")
    void lockoutMessageNamesTheUnlockPath() {
        expectLookup();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN)).isNotNull();
        }

        assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN))
                .hasMessageContaining("reset your password");
    }

    @Test
    @DisplayName("a locked account is refused even with the right password")
    void correctPasswordDoesNotBypassTheLock() {
        expectLookup();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN)).isNotNull();
        }

        /* Letting a correct password through during the window would exempt precisely the
           attacker whose guessing just succeeded, which is the one case the lockout is for. */
        assertThatThrownBy(() -> authService.login(attempt(PASSWORD), ORIGIN))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.LOCKED);
        verify(userSessionService, never()).open(any());
    }

    @Test
    @DisplayName("no BCrypt hash is computed while the account is locked")
    void lockedAccountsCostNothingToRefuse() {
        expectLookup();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN)).isNotNull();
        }
        int attemptsAfterLocking = user.getFailedLoginAttempts();

        assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN)).isNotNull();

        /* If the password were verified before the lock check, the attempt would also have been
           counted -- and every attempt in a sustained attack would still cost a full BCrypt hash,
           turning the lockout into a way to spend the server's CPU rather than save it. */
        assertThat(user.getFailedLoginAttempts()).isEqualTo(attemptsAfterLocking);
    }

    @Test
    @DisplayName("a correct password clears the count, so ordinary typos never accumulate")
    void successResetsTheCount() {
        expectLookup();
        when(membershipRepository.findByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(Membership.builder()
                        .userId(user.getId())
                        .organizationId(UUID.randomUUID())
                        .role(MembershipRole.OWNER)
                        .build()));
        when(jwtUtil.generateRefreshToken(any(), any())).thenReturn("refresh");
        when(jwtUtil.getJtiFromToken("refresh")).thenReturn(UUID.randomUUID().toString());
        when(jwtUtil.getExpirationFromToken("refresh"))
                .thenReturn(new java.util.Date(System.currentTimeMillis() + 86_400_000L));

        assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN)).isNotNull();
        assertThatThrownBy(() -> authService.login(attempt("wrong"), ORIGIN)).isNotNull();
        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);

        authService.login(attempt(PASSWORD), ORIGIN);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockoutExpiresAt()).isNull();
    }

    @Test
    @DisplayName("an unknown email is still just invalid credentials — nothing to count against")
    void unknownEmailIsUnchanged() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        LoginRequest request = new LoginRequest();
        request.setEmail("nobody@example.com");
        request.setPassword("whatever");

        assertThatThrownBy(() -> authService.login(request, ORIGIN))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
