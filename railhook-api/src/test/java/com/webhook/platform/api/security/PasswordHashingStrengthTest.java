package com.webhook.platform.api.security;

import com.webhook.platform.api.config.PasswordEncoderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHashingStrengthTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Test
    @DisplayName("a hash written at the old cost of 10 still verifies under the new encoder")
    void existingHashesStillVerify() {
        String writtenAtTen = new BCryptPasswordEncoder(10).encode(PASSWORD);

        BCryptPasswordEncoder raised = new PasswordEncoderConfig().passwordEncoder(12);

        assertThat(raised.encode(PASSWORD)).startsWith("$2a$12$");
        assertThat(raised.matches(PASSWORD, writtenAtTen)).isTrue();
        assertThat(raised.matches("something else", writtenAtTen)).isFalse();
    }

    @Test
    @DisplayName("the cost is configurable, so small hardware can tune it down")
    void costIsConfigurable() {
        assertThat(new PasswordEncoderConfig().passwordEncoder(4).encode(PASSWORD)).startsWith("$2a$04$");
        assertThat(new PasswordEncoderConfig().passwordEncoder(13).encode(PASSWORD)).startsWith("$2a$13$");
    }

    @Test
    @DisplayName("a cost outside BCrypt's own range fails at startup, not at the first login")
    void nonsenseCostIsRejectedEagerly() {
        assertThatThrownBy(() -> new PasswordEncoderConfig().passwordEncoder(3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordEncoderConfig().passwordEncoder(32))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
