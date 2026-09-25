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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
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
 * The bound on how many passwords may be guessed against one account.
 *
 * <p>These pin the four properties that make the lockout worth having without making it a
 * weapon: it counts only consecutive recent failures, it never fires on a correct password, it
 * always lapses on its own, and a password reset lifts it immediately. See
 * {@link AccountLockoutService} for the reasoning behind each.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountLockoutService — progressive lockout with a self-service way out")
class AccountLockoutServiceTest {

    private static final String PASSWORD = "the-right-password";
    private static final SessionOrigin ORIGIN =
            SessionOrigin.of(SessionClient.WEB, "Mozilla/5.0", "198.51.100.4");

    @Mock
    private UserRepository userRepository;

    private AccountLockoutService service(boolean enabled) {
        return new AccountLockoutService(userRepository, enabled, 5, 60, 900, 60);
    }

    private static User user() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("someone@example.com")
                .passwordHash("irrelevant")
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("failures below the threshold do not lock the account")
    void belowThresholdStaysOpen() {
        AccountLockoutService service = service(true);
        User user = user();

        for (int i = 0; i < 4; i++) {
            service.recordFailure(user);
        }

        assertThat(user.getFailedLoginAttempts()).isEqualTo(4);
        assertThat(service.isLocked(user)).isFalse();
        assertThat(user.getLockoutExpiresAt()).isNull();
    }

    @Test
    @DisplayName("the fifth consecutive failure locks the account for a minute")
    void thresholdLocks() {
        AccountLockoutService service = service(true);
        User user = user();

        for (int i = 0; i < 5; i++) {
            service.recordFailure(user);
        }

        assertThat(service.isLocked(user)).isTrue();
        assertThat(service.remainingLockout(user))
                .isBetween(Duration.ofSeconds(50), Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("each further failure doubles the wait, and the wait is capped")
    void lockoutIsProgressiveAndCapped() {
        AccountLockoutService service = service(true);
        User user = user();

        for (int i = 0; i < 5; i++) {
            service.recordFailure(user);
        }
        Duration first = service.remainingLockout(user);

        service.recordFailure(user);
        Duration second = service.remainingLockout(user);

        assertThat(second).isGreaterThan(first);
        assertThat(second).isBetween(Duration.ofSeconds(110), Duration.ofSeconds(120));

        /* Far past any plausible number of typos: the cap, not an ever-growing wait. A lockout
           that keeps growing is a permanent lockout with extra steps, and a permanent lockout
           against a known email address is a denial of service. */
        for (int i = 0; i < 40; i++) {
            service.recordFailure(user);
        }
        assertThat(service.remainingLockout(user))
                .as("capped at fifteen minutes however many attempts follow")
                .isBetween(Duration.ofSeconds(890), Duration.ofSeconds(900));
    }

    @Test
    @DisplayName("a lockout lapses by itself — nothing has to unlock it")
    void lockoutExpiresOnItsOwn() {
        AccountLockoutService service = service(true);
        User user = user();
        user.setLockoutExpiresAt(Instant.now().minusSeconds(1));

        assertThat(service.isLocked(user)).isFalse();
        assertThat(service.remainingLockout(user)).isZero();
    }

    @Test
    @DisplayName("a success clears the count, so a correct password never accumulates a lockout")
    void successClearsTheCount() {
        AccountLockoutService service = service(true);
        User user = user();
        service.recordFailure(user);
        service.recordFailure(user);

        service.clearFailures(user);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastFailedLoginAt()).isNull();
        assertThat(user.getLockoutExpiresAt()).isNull();
    }

    @Test
    @DisplayName("a password reset lifts an active lockout — the way out that needs nobody's help")
    void resetLiftsAnActiveLockout() {
        AccountLockoutService service = service(true);
        User user = user();
        for (int i = 0; i < 6; i++) {
            service.recordFailure(user);
        }
        assertThat(service.isLocked(user)).isTrue();

        service.clearFailures(user);

        assertThat(service.isLocked(user)).isFalse();
    }

    @Test
    @DisplayName("stale failures do not count — five typos across a year are not five in a row")
    void staleFailuresAreForgotten() {
        AccountLockoutService service = service(true);
        User user = user();
        user.setFailedLoginAttempts(4);
        user.setLastFailedLoginAt(Instant.now().minus(Duration.ofHours(3)));

        service.recordFailure(user);

        assertThat(user.getFailedLoginAttempts())
                .as("the four old failures fell outside the window, so this is the first")
                .isEqualTo(1);
        assertThat(service.isLocked(user)).isFalse();
    }

    @Test
    @DisplayName("clearing an account with nothing to clear does not write a row")
    void clearingIsANoOpWhenClean() {
        AccountLockoutService service = service(true);

        service.clearFailures(user());

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("disabled means disabled: no counting, no locking")
    void canBeTurnedOff() {
        AccountLockoutService service = service(false);
        User user = user();

        for (int i = 0; i < 20; i++) {
            service.recordFailure(user);
        }

        assertThat(service.isLocked(user)).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("a threshold or cap that cannot mean anything fails at startup")
    void nonsenseConfigurationIsRejected() {
        assertThatThrownBy(() -> new AccountLockoutService(userRepository, true, 0, 60, 900, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTH_LOCKOUT_THRESHOLD");
        assertThatThrownBy(() -> new AccountLockoutService(userRepository, true, 5, 900, 60, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTH_LOCKOUT_MAX_SECONDS");
    }

    /**
     * Where the lockout meets the login.
     *
     * <p>The tests above cover the counting; these cover the three decisions the
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
    @Nested
    @DisplayName("AuthService.login — what the lockout does to a sign-in")
    class LoginLockout {

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

        @BeforeEach
        void setUp() {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
            lockout = new AccountLockoutService(userRepository, true, 3, 60, 900, 60);
            authService = new AuthService(userRepository, organizationRepository, membershipRepository,
                    planRepository, jwtUtil, encoder, tokenBlacklistService, userSessionService,
                    lockout, emailService,
                    mock(VerificationMailBudget.class), mock(OnboardingMailService.class), false);

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
                    .thenReturn(new Date(System.currentTimeMillis() + 86_400_000L));

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
}
