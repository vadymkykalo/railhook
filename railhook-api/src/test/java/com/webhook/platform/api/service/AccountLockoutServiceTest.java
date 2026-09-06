package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
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
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
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
}
