package com.webhook.platform.api.security;

import com.webhook.platform.api.config.PasswordEncoderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The work factor a password hash is stored at.
 *
 * <p>Both services that hash a password used to call {@code new BCryptPasswordEncoder()}, which
 * is cost 10 — a figure from 2010 that current hardware chews through fast enough that a leaked
 * {@code users} table is a wordlist away from being a credential dump. The cost now comes from
 * {@link PasswordEncoderConfig}, so an operator on small hardware can lower it and a larger
 * deployment can raise it without a rebuild.
 *
 * <p>The part worth pinning is that raising it is not a migration: BCrypt writes its own cost
 * into the {@code $2a$NN$} prefix of every hash, so an encoder configured for 12 still verifies
 * a hash written at 10. Nobody is locked out or forced through a reset for the change to land,
 * and the next password change rewrites that user's hash at the new cost by itself.
 */
class PasswordHashingStrengthTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Test
    @DisplayName("the default cost is 12, not BCrypt's 10")
    void defaultCostIsTwelve() {
        BCryptPasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder(12);

        assertThat(encoder.encode(PASSWORD))
                .as("BCrypt stamps its own cost into the hash prefix")
                .startsWith("$2a$12$");
    }

    @Test
    @DisplayName("a hash written at the old cost of 10 still verifies under the new encoder")
    void existingHashesStillVerify() {
        String writtenAtTen = new BCryptPasswordEncoder(10).encode(PASSWORD);
        assertThat(writtenAtTen).startsWith("$2a$10$");

        BCryptPasswordEncoder raised = new PasswordEncoderConfig().passwordEncoder(12);

        assertThat(raised.matches(PASSWORD, writtenAtTen))
                .as("raising the cost must not invalidate every stored password")
                .isTrue();
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTH_BCRYPT_STRENGTH");
        assertThatThrownBy(() -> new PasswordEncoderConfig().passwordEncoder(32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTH_BCRYPT_STRENGTH");
    }

    /**
     * Not an assertion about any particular machine — the bound is loose enough that only a
     * misconfiguration trips it. It exists so the number is printed by a run rather than guessed
     * at: cost 12 is four times the work of cost 10, and that multiplier is the whole cost of
     * the change, paid once per login.
     */
    @Test
    @DisplayName("the added latency is a fraction of a second per login")
    void costOfVerificationIsBounded() {
        BCryptPasswordEncoder ten = new BCryptPasswordEncoder(10);
        BCryptPasswordEncoder twelve = new PasswordEncoderConfig().passwordEncoder(12);
        String atTen = ten.encode(PASSWORD);
        String atTwelve = twelve.encode(PASSWORD);

        long tenNanos = timeVerify(ten, atTen);
        long twelveNanos = timeVerify(twelve, atTwelve);

        System.out.printf("bcrypt verify: cost 10 = %.0f ms, cost 12 = %.0f ms, added %.0f ms%n",
                tenNanos / 1_000_000.0, twelveNanos / 1_000_000.0,
                (twelveNanos - tenNanos) / 1_000_000.0);

        assertThat(twelveNanos / 1_000_000.0)
                .as("cost 12 should verify well inside a second on any machine this runs on")
                .isLessThan(2_000);
    }

    private static long timeVerify(BCryptPasswordEncoder encoder, String hash) {
        encoder.matches(PASSWORD, hash); // warm the JIT
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            encoder.matches(PASSWORD, hash);
        }
        return (System.nanoTime() - start) / 3;
    }
}
