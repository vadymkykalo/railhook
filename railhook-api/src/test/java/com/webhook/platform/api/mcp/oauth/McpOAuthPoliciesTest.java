package com.webhook.platform.api.mcp.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The small rules the authorization server's security rests on, checked without a database:
 * which redirect URIs can be registered and matched, which resource a token may be asked for, and
 * PKCE.
 */
class McpOAuthPoliciesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://claude.ai/api/mcp/auth_callback",
            "https://chatgpt.com/connector_platform_oauth_redirect",
            "http://localhost:6274/oauth/callback",
            "http://127.0.0.1:33418/callback",
            "http://[::1]:8080/cb",
    })
    void acceptsHttpsAndLoopbackRedirects(String uri) {
        assertThat(RedirectUris.problemWith(uri)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/cb",
            "http://localhost.evil.com/cb",
            "https://claude.ai/cb#fragment",
            "https://user:pass@claude.ai/cb",
            "javascript:alert(1)",
            "cursor://anysphere.cursor-mcp/oauth/callback",
            "file:///etc/passwd",
            "/relative/path",
            "not a uri",
    })
    void refusesRedirectsThatCouldLeakACode(String uri) {
        assertThat(RedirectUris.problemWith(uri)).isNotNull();
    }

    @Test
    void matchesRedirectsExactlyExceptForTheLoopbackPort() {
        List<String> registered = List.of("https://claude.ai/api/mcp/auth_callback", "http://127.0.0.1:1234/cb");
        assertThat(RedirectUris.isRegistered("https://claude.ai/api/mcp/auth_callback", registered)).isTrue();
        assertThat(RedirectUris.isRegistered("https://claude.ai/api/mcp/auth_callback/", registered)).isFalse();
        assertThat(RedirectUris.isRegistered("https://CLAUDE.ai/api/mcp/auth_callback", registered)).isFalse();
        assertThat(RedirectUris.isRegistered("https://claude.ai/api/mcp/auth_callback?x=1", registered)).isFalse();
        assertThat(RedirectUris.isRegistered("http://127.0.0.1:5555/cb", registered)).isTrue();
        assertThat(RedirectUris.isRegistered("http://127.0.0.1:5555/other", registered)).isFalse();
        assertThat(RedirectUris.isRegistered("http://localhost:1234/cb", registered)).isFalse();
        assertThat(RedirectUris.isRegistered(null, registered)).isFalse();
    }

    @Test
    void recognisesThisServerAsTheResource() {
        McpOAuthSettings settings = new McpOAuthSettings(true, true, "https://railhook.io/");
        assertThat(settings.issuer()).isEqualTo("https://railhook.io");
        assertThat(settings.resource()).isEqualTo("https://railhook.io/mcp");
        assertThat(settings.resourceMetadataUrl()).isEqualTo("https://railhook.io/.well-known/oauth-protected-resource/mcp");

        assertThat(settings.isThisResource("https://railhook.io/mcp")).isTrue();
        assertThat(settings.isThisResource("https://RAILHOOK.io/mcp/")).isTrue();
        assertThat(settings.isThisResource("https://railhook.io")).isTrue();
        assertThat(settings.isThisResource("https://railhook.io/mcp#x")).isFalse();
        assertThat(settings.isThisResource("https://railhook.io.evil.com/mcp")).isFalse();
        assertThat(settings.isThisResource("https://railhook.io/other")).isFalse();
        assertThat(settings.isThisResource("railhook.io/mcp")).isFalse();
    }

    @Test
    void isOffWhenEitherTheMcpServerOrOAuthIs() {
        assertThat(new McpOAuthSettings(false, true, "https://x.io").enabled()).isFalse();
        assertThat(new McpOAuthSettings(true, false, "https://x.io").enabled()).isFalse();
        assertThat(new McpOAuthSettings(true, true, "https://x.io").enabled()).isTrue();
    }

    @Test
    void verifiesPkceS256() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        // The worked example from RFC 7636 Appendix B.
        String challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertThat(OAuthSecrets.isS256Challenge(challenge)).isTrue();
        assertThat(OAuthSecrets.pkceMatches(verifier, challenge)).isTrue();
        assertThat(OAuthSecrets.pkceMatches(verifier + "x", challenge)).isFalse();
        assertThat(OAuthSecrets.pkceMatches("short", challenge("short"))).isFalse();
        assertThat(OAuthSecrets.pkceMatches(null, challenge)).isFalse();
        assertThat(OAuthSecrets.isS256Challenge("plain-challenge")).isFalse();
    }

    @Test
    void mintsPrefixedUnguessableSecretsAndStoresOnlyTheirHash() {
        String token = OAuthSecrets.mint(OAuthSecrets.ACCESS_TOKEN_PREFIX);
        assertThat(token).startsWith("rhat_").hasSize(5 + 43);
        assertThat(OAuthSecrets.mint(OAuthSecrets.ACCESS_TOKEN_PREFIX)).isNotEqualTo(token);
        String hash = OAuthSecrets.hash(token);
        assertThat(hash).doesNotContain(token.substring(5));
        assertThat(OAuthSecrets.matches(token, hash)).isTrue();
        assertThat(OAuthSecrets.matches(token + "x", hash)).isFalse();
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
