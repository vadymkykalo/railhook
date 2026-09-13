package com.webhook.platform.api.service.signin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the dashboard lands after signing in with Google. The value travels through Google and
 * back, so it is attacker-supplied: anything that could leave this origin is an open redirect
 * on the sign-in page, which is the most convincing place a phishing link can start.
 */
class SafeReturnPathTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin/projects",
            "/admin/projects/0b7c/events?page=2",
            "/device?code=ABCD-EFGH",
    })
    void keepsAPathOnThisOrigin(String path) {
        assertThat(SafeReturnPath.of(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/admin",
            "//evil.example/admin",
            "/\\evil.example",
            "\\\\evil.example",
            "javascript:alert(1)",
            "admin/projects",
            "/admin\r\nSet-Cookie: x=1",
    })
    void replacesAnythingThatCouldLeaveIt(String path) {
        assertThat(SafeReturnPath.of(path)).isEqualTo(SafeReturnPath.DEFAULT);
    }

    @Test
    void usesTheDashboardWhenThereIsNothing() {
        assertThat(SafeReturnPath.of(null)).isEqualTo(SafeReturnPath.DEFAULT);
        assertThat(SafeReturnPath.of("")).isEqualTo(SafeReturnPath.DEFAULT);
        assertThat(SafeReturnPath.of("/" + "a".repeat(600))).isEqualTo(SafeReturnPath.DEFAULT);
    }
}
