package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * How many wrong passwords an account tolerates before it stops answering for a while.
 *
 * <h2>What this is for</h2>
 *
 * <p>{@link AuthRateLimiterService} bounds login attempts at ten a minute per IP <em>and</em> ten
 * a minute per email address. Ten a minute is 14,400 a day against one account, which is a
 * perfectly workable rate for a dictionary attack on a human-chosen password — and when Redis is
 * unavailable that limiter degrades to a per-instance in-memory bucket, so the real ceiling is
 * multiplied by however many API replicas are running. Rate limiting answers "how fast", not
 * "how many"; this answers "how many".
 *
 * <p>The counter lives in Postgres, not in Redis, precisely because of that fallback: the
 * database is shared by every replica and has no degraded mode in which the count quietly
 * becomes per-instance.
 *
 * <h2>Lockout is itself an attack, so this one is built to be a poor weapon</h2>
 *
 * <p>Anything that locks an account on failed attempts hands an attacker who knows an email
 * address a way to keep its owner out. That is a real trade and it is decided here rather than
 * left implicit:
 *
 * <ul>
 *   <li><b>Every lockout expires on its own.</b> There is no administrator-only unlock, because
 *       an unlock that needs another human is what turns a nuisance into an outage — and in a
 *       self-hosted product that human is frequently the locked-out person.</li>
 *   <li><b>The window is short and capped</b> — a minute at the threshold, doubling per further
 *       failure, capped at fifteen. Progressive because a real user who mistypes twice more
 *       should not be treated like the thousandth guess; capped because the value of a longer
 *       window to a defender falls off quickly while its value to a griefer does not.</li>
 *   <li><b>The account holder always has a way through.</b> A password reset clears the lockout
 *       outright, so someone locked out by a stranger is one email away from their account
 *       rather than waiting on anybody.</li>
 *   <li><b>A correct password is never what trips it.</b> The count is of consecutive failures
 *       and a success zeroes it, so an account in daily use never accumulates one.</li>
 *   <li><b>Failures go stale.</b> Two typos in March and three in June are not five consecutive
 *       failures; anything older than the failure window is dropped before counting.</li>
 * </ul>
 *
 * <p>What remains is that an attacker can cost a targeted address up to fifteen minutes at a
 * time, and only for as long as they keep spending attempts through the IP rate limiter to do
 * it. That is the accepted cost. The alternative — no bound at all on attempts per account —
 * costs the account itself.
 */
@Service
@Slf4j
public class AccountLockoutService {

    private final UserRepository userRepository;
    private final boolean enabled;
    private final int threshold;
    private final Duration initialLockout;
    private final Duration maxLockout;
    private final Duration failureWindow;

    public AccountLockoutService(
            UserRepository userRepository,
            @Value("${auth.lockout.enabled:true}") boolean enabled,
            @Value("${auth.lockout.threshold:5}") int threshold,
            @Value("${auth.lockout.initial-seconds:60}") long initialSeconds,
            @Value("${auth.lockout.max-seconds:900}") long maxSeconds,
            @Value("${auth.lockout.failure-window-minutes:60}") long failureWindowMinutes) {
        if (threshold < 1) {
            throw new IllegalArgumentException(
                    "AUTH_LOCKOUT_THRESHOLD must be at least 1, was " + threshold
                            + ". Set AUTH_LOCKOUT_ENABLED=false to turn lockout off instead.");
        }
        if (maxSeconds < initialSeconds) {
            throw new IllegalArgumentException(
                    "AUTH_LOCKOUT_MAX_SECONDS (" + maxSeconds + ") is below AUTH_LOCKOUT_INITIAL_SECONDS ("
                            + initialSeconds + "), which would cap the first lockout below its own length.");
        }
        this.userRepository = userRepository;
        this.enabled = enabled;
        this.threshold = threshold;
        this.initialLockout = Duration.ofSeconds(initialSeconds);
        this.maxLockout = Duration.ofSeconds(maxSeconds);
        this.failureWindow = Duration.ofMinutes(failureWindowMinutes);
    }

    /** How much longer this account is locked, or {@link Duration#ZERO} when it is not. */
    public Duration remainingLockout(User user) {
        if (!enabled || user.getLockoutExpiresAt() == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), user.getLockoutExpiresAt());
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public boolean isLocked(User user) {
        return !remainingLockout(user).isZero();
    }

    /**
     * Counts one failed password check, locking the account once the failures reach the
     * threshold.
     *
     * <p>System-scoped because it is reached from the login path, where no organization is
     * established yet — {@code users} carries no {@code @TenantId}, but Hibernate still needs a
     * scope to open a session in.
     */
    @SystemTenant("counts a failed login, which happens before any organization is known")
    @Transactional
    public void recordFailure(User user) {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();

        // Failures have to be consecutive *and* recent to mean anything. Without this, a user
        // who mistypes once every few months eventually locks themselves out of an account
        // nobody is attacking.
        int previous = user.getLastFailedLoginAt() != null
                && user.getLastFailedLoginAt().isAfter(now.minus(failureWindow))
                ? orZero(user.getFailedLoginAttempts())
                : 0;

        int attempts = previous + 1;
        user.setFailedLoginAttempts(attempts);
        user.setLastFailedLoginAt(now);

        if (attempts >= threshold) {
            Duration lockFor = lockoutFor(attempts);
            user.setLockoutExpiresAt(now.plus(lockFor));
            log.warn("Account {} locked for {}s after {} consecutive failed logins",
                    user.getId(), lockFor.toSeconds(), attempts);
        }

        userRepository.save(user);
    }

    /**
     * Forgets the failures for an account whose holder has just proved they are present — a
     * successful login, a password change, or a completed password reset. The reset case is the
     * load-bearing one: it is the unlock path for somebody a stranger locked out.
     */
    @SystemTenant("clears login failures on paths that run before, or without, an organization scope")
    @Transactional
    public void clearFailures(User user) {
        if (orZero(user.getFailedLoginAttempts()) == 0
                && user.getLockoutExpiresAt() == null
                && user.getLastFailedLoginAt() == null) {
            return;
        }
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockoutExpiresAt(null);
        userRepository.save(user);
    }

    /**
     * Doubling from the threshold, capped. The first lockout past the threshold is short enough
     * that a real user who has just remembered their password barely notices; by the time the
     * attempts are in the dozens it is the cap.
     */
    private Duration lockoutFor(int attempts) {
        int steps = Math.min(attempts - threshold, 20); // 2^20 minutes already dwarfs any cap
        Duration scaled = initialLockout.multipliedBy(1L << steps);
        return scaled.compareTo(maxLockout) > 0 ? maxLockout : scaled;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
