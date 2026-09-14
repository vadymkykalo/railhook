package com.webhook.platform.api.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class EmailAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {"a@b.co", "first.last+tag@mail.example.com", "x@a.b", "a@.b.c", "a@b..c"})
    void acceptsAnAddressShapedLikeOne(String email) {
        assertThat(EmailAddresses.isPlausible(email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "@b.co", "a@", "a@bco", "a@.co", "a@b.", "a@@b.co", "a@b@c.co",
            "a b@c.co", "a@b.c o", "a,b@c.co", "a@b;c.co", "<a@b.co>", "\"a\"@b.co", "a@(b).co", "a@b\t.co"})
    void refusesAnythingElse(String email) {
        assertThat(EmailAddresses.isPlausible(email)).isFalse();
    }

    @Test
    void refusesNullAndAddressesLongerThanTheLimit() {
        assertThat(EmailAddresses.isPlausible(null)).isFalse();
        assertThat(EmailAddresses.isPlausible("a".repeat(250) + "@b.co")).isFalse();
        assertThat(EmailAddresses.isPlausible("a".repeat(249) + "@b.co")).isTrue();
    }

    @Test
    void answersAtOnceOnADomainOfManyDots() {
        String hostile = "!@!." + "!.".repeat(100_000);
        assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
            assertThat(EmailAddresses.isPlausible(hostile)).isFalse();
        });
    }

    @Test
    void normalizesToTrimmedLowerCase() {
        assertThat(EmailAddresses.normalize("  Mixed.Case@Corp.Example ")).isEqualTo("mixed.case@corp.example");
        assertThat(EmailAddresses.normalize(null)).isNull();
    }
}
