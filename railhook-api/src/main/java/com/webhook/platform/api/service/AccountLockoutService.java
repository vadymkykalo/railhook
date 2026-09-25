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
 * Rate limiting bounds how fast; this bounds how many. Lockout always expires, caps at fifteen
 * minutes and is cleared by a password reset, so it is a poor weapon against an owner.
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

    @SystemTenant("counts a failed login, which happens before any organization is known")
    @Transactional
    public void recordFailure(User user) {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();

        // Only recent failures count, or occasional typos over months would add up to a lockout.
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

    private Duration lockoutFor(int attempts) {
        int steps = Math.min(attempts - threshold, 20); // 2^20 minutes already dwarfs any cap
        Duration scaled = initialLockout.multipliedBy(1L << steps);
        return scaled.compareTo(maxLockout) > 0 ? maxLockout : scaled;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
