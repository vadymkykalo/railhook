package com.webhook.platform.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Default cost 12 is roughly a quarter-second per login. Changing it later is safe: BCrypt stores
 * the cost in each hash, so existing hashes keep verifying.
 */
@Configuration
public class PasswordEncoderConfig {

    // Checked at startup: an out-of-range cost would otherwise fail every login instead.
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
