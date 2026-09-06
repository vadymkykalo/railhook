package com.webhook.platform.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * One BCrypt encoder, at a cost an operator chooses.
 *
 * <p>{@code AuthService} and {@code MembershipService} each used to construct their own
 * {@code new BCryptPasswordEncoder()}, so the work factor protecting every stored password was
 * BCrypt's own default of 10 — and was not written down anywhere, let alone configurable.
 *
 * <p>Cost is a doubling scale: each step is twice the work for an attacker and twice the work
 * for a login. 12 is the default here because it is roughly a quarter-second on the kind of
 * hardware this runs on, which is negligible against the once-per-session price of a login and
 * four times the price of the old default for anyone grinding a stolen table. An operator on a
 * small VPS can set {@code AUTH_BCRYPT_STRENGTH} lower; raising it later costs nothing, because
 * BCrypt stores the cost in the hash and every existing hash keeps verifying at whatever cost it
 * was written with — see {@code PasswordHashingStrengthTest}.
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * Rejected eagerly rather than at the first login: {@code BCryptPasswordEncoder} itself
     * throws on a value outside 4..31, and a container that starts and then fails every
     * authentication is far harder to diagnose than one that refuses to start.
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder(@Value("${auth.bcrypt.strength:12}") int strength) {
        if (strength < 4 || strength > 31) {
            throw new IllegalArgumentException(
                    "AUTH_BCRYPT_STRENGTH must be between 4 and 31 (BCrypt's own range), was " + strength
                            + ". 12 is the default; lower it only on hardware where a login is measurably slow.");
        }
        return new BCryptPasswordEncoder(strength);
    }
}
