package com.webhook.platform.api.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAdminEmailsTest {

    @Test
    void unsetMeansNobody() {
        assertThat(new PlatformAdminEmails(null).isEmpty()).isTrue();
        assertThat(new PlatformAdminEmails("").isListed("anyone@example.com")).isFalse();
        assertThat(new PlatformAdminEmails(" , ,").isEmpty()).isTrue();
    }

    @Test
    void addressesAreComparedWithoutCaseOrSurroundingSpace() {
        PlatformAdminEmails emails = new PlatformAdminEmails(" Ops@Example.com ,second@example.com");

        assertThat(emails.isListed("ops@example.com")).isTrue();
        assertThat(emails.isListed("  OPS@EXAMPLE.COM ")).isTrue();
        assertThat(emails.isListed("second@example.com")).isTrue();
        assertThat(emails.size()).isEqualTo(2);
    }

    @Test
    void nothingPartialMatches() {
        PlatformAdminEmails emails = new PlatformAdminEmails("ops@example.com");

        assertThat(emails.isListed("ops@example.com.evil.test")).isFalse();
        assertThat(emails.isListed("xops@example.com")).isFalse();
        assertThat(emails.isListed(null)).isFalse();
        assertThat(emails.isListed("")).isFalse();
    }
}
